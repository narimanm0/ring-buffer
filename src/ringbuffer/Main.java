package ringbuffer;

/**
        Simple demo for the ring buffer. We run one writer and three readers, each reading at a different speed.
        This makes it easy to see what happens when readers fall behind.
        What usually happens:
              - FastReader keeps up and rarely misses anything
              - NormalReader sometimes falls behind and skips a few items
              - SlowReader is too slow and misses a lot once the buffer wraps
 */
public class Main {

    private static final int BUFFER_CAPACITY = 8;
    private static final int ITEMS_TO_WRITE  = 40;

    public static void main(String[] args) throws InterruptedException {

        // Create buffer, writer, and readers.
        RingBuffer<String> ringBuffer = new RingBuffer<>(BUFFER_CAPACITY);
        Writer<String> writer = new Writer<>(ringBuffer);

        ReaderHandle<String> fastReader =
                ringBuffer.registerReader("FastReader");
        ReaderHandle<String> normalReader =
                ringBuffer.registerReader("NormalReader");
        ReaderHandle<String> slowReader =
                ringBuffer.registerReader("SlowReader");

        // Writer thread.
        Thread writerThread = new Thread(() -> {
            for (int i = 1; i <= ITEMS_TO_WRITE; i++) {
                writer.write("item-" + i);
                System.out.printf(
                        "[Writer]  wrote item-%-3d (writeIndex=%d)%n",
                        i, ringBuffer.getWriteIndex()
                );
                sleep(50);
            }
            System.out.println("[Writer]  done.");
        }, "WriterThread");

        // Reader threads (different speeds)
        Thread fastThread =
                readerThread(fastReader, 10, "fast");
        Thread normalThread =
                readerThread(normalReader, 80, "normal");
        Thread slowThread =
                readerThread(slowReader, 200, "slow");

        // Start everything.
        writerThread.start();
        fastThread.start();
        normalThread.start();
        slowThread.start();

        // Wait for all threads to finish.
        writerThread.join();
        fastThread.join();
        normalThread.join();
        slowThread.join();

        // Print final results.
        System.out.println("\n═══ Summary ═══════════════════════════════");
        System.out.printf("  %-14s read pos: %-4d  missed: %d%n",
                fastReader.getName(),
                fastReader.getReadPos(),
                fastReader.getMissedCount());
        System.out.printf("  %-14s read pos: %-4d  missed: %d%n",
                normalReader.getName(),
                normalReader.getReadPos(),
                normalReader.getMissedCount());
        System.out.printf("  %-14s read pos: %-4d  missed: %d%n",
                slowReader.getName(),
                slowReader.getReadPos(),
                slowReader.getMissedCount());
        System.out.println("═══════════════════════════════════════════");

        // Clean up reader registrations.
        fastReader.unregister();
        normalReader.unregister();
        slowReader.unregister();
    }

    // Create a reader thread that keeps reading with a fixed delay between each read.
    private static Thread readerThread(ReaderHandle<String> handle,
                                       long delayMs,
                                       String speedLabel) {
        return new Thread(() -> {
            try {
                // Keep reading until the writer has produced everything.
                while (handle.getReadPos() < ITEMS_TO_WRITE) {
                    String item = handle.read();
                    System.out.printf(
                            "  [%-14s] read: %s%n",
                            handle.getName(),
                            item
                    );
                    sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.printf(
                    "  [%-14s] done. missed=%d%n",
                    handle.getName(),
                    handle.getMissedCount()
            );
        }, handle.getName() + "Thread");
    }

    // Small helper to avoid repeating try/catch everywhere.
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}