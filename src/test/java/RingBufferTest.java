import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RingBufferTest {
    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<>(0));
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<>(-1));
    }

    @Test
    void capacityReturnsConfiguredCapacity() {
        RingBuffer<String> buffer = new RingBuffer<>(7);

        assertEquals(7, buffer.capacity());
    }

    @Test
    void newReadersOnEmptyBufferReadEmptyWithoutMoving() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        RingReader<String> oldest = buffer.createReaderAtOldest();
        RingReader<String> latest = buffer.createReaderAtLatest();

        assertEmptyRead(oldest.read());
        assertEmptyRead(latest.read());
        assertEquals(0, oldest.position());
        assertEquals(0, latest.position());
    }

    @Test
    void readerAtOldestConsumesExistingEntriesInOrder() {
        RingBuffer<String> buffer = new RingBuffer<>(5);
        RingWriter<String> writer = buffer.writer();
        writer.write("a");
        writer.write("b");
        writer.write("c");

        RingReader<String> reader = buffer.createReaderAtOldest();

        assertValueRead(reader.read(), "a", 0);
        assertValueRead(reader.read(), "b", 0);
        assertValueRead(reader.read(), "c", 0);
        assertEmptyRead(reader.read());
        assertEquals(3, reader.position());
    }

    @Test
    void readerAtLatestStartsAfterAlreadyWrittenEntries() {
        RingBuffer<String> buffer = new RingBuffer<>(5);
        RingWriter<String> writer = buffer.writer();
        writer.write("old-1");
        writer.write("old-2");

        RingReader<String> reader = buffer.createReaderAtLatest();

        assertEmptyRead(reader.read());
        assertEquals(2, reader.position());

        writer.write("new");

        assertValueRead(reader.read(), "new", 0);
        assertEquals(3, reader.position());
    }

    @Test
    void slowReaderReportsMissedEntriesWhenOverwritten() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        RingWriter<String> writer = buffer.writer();
        RingReader<String> reader = buffer.createReaderAtOldest();

        writeMessages(writer, 0, 5);

        assertValueRead(reader.read(), "msg-2", 2);
        assertEquals(3, reader.position());
        assertValueRead(reader.read(), "msg-3", 0);
        assertValueRead(reader.read(), "msg-4", 0);
        assertEmptyRead(reader.read());
    }

    @Test
    void readerCreatedAfterOverwriteStartsAtOldestAvailableWithoutMissedCount() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        RingWriter<String> writer = buffer.writer();
        writeMessages(writer, 0, 6);

        RingReader<String> reader = buffer.createReaderAtOldest();

        assertEquals(3, reader.position());
        assertValueRead(reader.read(), "msg-3", 0);
        assertValueRead(reader.read(), "msg-4", 0);
        assertValueRead(reader.read(), "msg-5", 0);
        assertEmptyRead(reader.read());
    }

    @Test
    void multipleReadersKeepIndependentPositionsAndMissedCounts() {
        RingBuffer<String> buffer = new RingBuffer<>(4);
        RingWriter<String> writer = buffer.writer();
        RingReader<String> fastReader = buffer.createReaderAtOldest();
        RingReader<String> slowReader = buffer.createReaderAtOldest();

        writeMessages(writer, 0, 3);

        assertValueRead(fastReader.read(), "msg-0", 0);
        assertValueRead(fastReader.read(), "msg-1", 0);

        writeMessages(writer, 3, 4);

        assertValueRead(fastReader.read(), "msg-3", 1);
        assertValueRead(slowReader.read(), "msg-3", 3);
        assertEquals(4, fastReader.position());
        assertEquals(4, slowReader.position());
    }

    @Test
    void capacityOneKeepsOnlyLatestEntryAndReportsAllSkippedItems() {
        RingBuffer<String> buffer = new RingBuffer<>(1);
        RingWriter<String> writer = buffer.writer();
        RingReader<String> reader = buffer.createReaderAtOldest();

        writer.write("first");
        writer.write("second");
        writer.write("third");

        assertValueRead(reader.read(), "third", 2);
        assertEmptyRead(reader.read());
    }

    @Test
    void writerRejectsNullAndDoesNotAdvanceSequence() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        RingWriter<String> writer = buffer.writer();
        RingReader<String> reader = buffer.createReaderAtOldest();

        assertThrows(NullPointerException.class, () -> writer.write(null));

        assertEquals(0, reader.position());
        assertEmptyRead(reader.read());

        writer.write("valid");

        assertValueRead(reader.read(), "valid", 0);
        assertEquals(1, reader.position());
    }

    @Test
    void readResultFactoriesExposeValueMissedCountAndStringRepresentation() {
        ReadResult<String> empty = ReadResult.empty();
        ReadResult<String> value = ReadResult.value("payload", 4);

        assertEquals(Optional.empty(), empty.value());
        assertEquals(0, empty.countMissed());
        assertEquals("ReadResult{value=Optional.empty, countMissed=0}", empty.toString());

        assertEquals(Optional.of("payload"), value.value());
        assertEquals(4, value.countMissed());
        assertEquals("ReadResult{value=Optional[payload], countMissed=4}", value.toString());
    }

    @Test
    void writerAndReadersCanRunConcurrentlyWithoutLosingReaderInvariants() {
        assertDoesNotThrow(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            RingBuffer<Integer> buffer = new RingBuffer<>(32);
            RingWriter<Integer> writer = buffer.writer();
            int readerCount = 4;
            int writes = 1_000;
            ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean writerDone = new AtomicBoolean(false);
            AtomicLong totalObservedValues = new AtomicLong();

            Future<?> writerFuture = executor.submit(() -> {
                await(start);
                for (int i = 0; i < writes; i++) {
                    writer.write(i);
                    if (i % 10 == 0) {
                        Thread.yield();
                    }
                }
                writerDone.set(true);
            });

            List<Future<Long>> readerFutures = new ArrayList<>();
            for (int i = 0; i < readerCount; i++) {
                RingReader<Integer> reader = buffer.createReaderAtOldest();
                readerFutures.add(executor.submit(readUntilCaughtUp(reader, writes, writerDone)));
            }

            start.countDown();
            writerFuture.get(2, TimeUnit.SECONDS);

            long observedByAllReaders = 0;
            for (Future<Long> future : readerFutures) {
                observedByAllReaders += future.get(2, TimeUnit.SECONDS);
            }
            totalObservedValues.set(observedByAllReaders);

            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(totalObservedValues.get() > 0);
        }));
    }

    private static Callable<Long> readUntilCaughtUp(
            RingReader<Integer> reader,
            int totalWrites,
            AtomicBoolean writerDone) {
        return () -> {
            long observed = 0;
            int previousValue = -1;
            while (!writerDone.get() || reader.position() < totalWrites) {
                ReadResult<Integer> result = reader.read();
                assertTrue(result.countMissed() >= 0);
                if (result.value().isPresent()) {
                    int value = result.value().get();
                    assertTrue(value > previousValue);
                    previousValue = value;
                    observed++;
                } else {
                    Thread.yield();
                }
            }
            return observed;
        };
    }

    private static void writeMessages(RingWriter<String> writer, int startInclusive, int count) {
        for (int i = startInclusive; i < startInclusive + count; i++) {
            writer.write("msg-" + i);
        }
    }

    private static void assertValueRead(ReadResult<String> result, String expectedValue, long expectedMissed) {
        assertEquals(Optional.of(expectedValue), result.value());
        assertEquals(expectedMissed, result.countMissed());
    }

    private static void assertEmptyRead(ReadResult<?> result) {
        assertFalse(result.value().isPresent());
        assertEquals(0, result.countMissed());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
