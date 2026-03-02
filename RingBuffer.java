package ringbuffer;

import java.util.ArrayList;
import java.util.List;

/**
    RingBuffer is the core data structure. It holds a fixed-size circular array of items.
    A single writer writes into the buffer; if full, it overwrites the oldest entry.
    Multiple readers each track their own read position via ReaderHandle objects.
    Thread-safety:
        all structural modifications (write, reader registration, lap/sequence counters) are guarded by the intrinsic lock on this object.
 */
public class RingBuffer<T> {

    // Circular array
    private final Object[] buffer;
    private final int capacity;

    private final long[] sequence;

    // Absolute write index.
    private long writeIndex = 0;

    // Registered readers that are kept so buffer can notify them on overwrite.
    private final List<ReaderHandle<T>> readers = new ArrayList<>();

    public RingBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.sequence = new long[capacity];
    }

    public synchronized void write(T item) {
        if (item == null) throw new IllegalArgumentException("Null items are not allowed");

        int slot = (int) (writeIndex % capacity);
        buffer[slot] = item;
        sequence[slot]++; // mark slot as updated

        writeIndex++;

        // If overwrite happened, advance any reader that was pointing at the overwritten slot so it doesn't return stale data.
        if (writeIndex > capacity) {
            long overwrittenAbsIndex = writeIndex - capacity - 1;
            for (ReaderHandle<T> reader : readers) {
                reader.onOverwrite(overwrittenAbsIndex);
            }
        }

        // Wake readers that may be waiting for data.
        notifyAll(); 
    }

    @SuppressWarnings("unchecked")
    synchronized T readAt(long readPos) throws InterruptedException {
        // Wait until the writer has written past this position.
        while (writeIndex <= readPos) {
            wait();
        }
        int slot = (int) (readPos % capacity);
        return (T) buffer[slot];
    }

    // Return the current absolute write index.
    synchronized long getWriteIndex() {
        return writeIndex;
    }

    // Register a new reader and returns its handle.
    public synchronized ReaderHandle<T> registerReader(String name) {
        ReaderHandle<T> handle = new ReaderHandle<>(this, writeIndex, name);
        readers.add(handle);
        return handle;
    }

    // Unregister a reader.
    synchronized void unregisterReader(ReaderHandle<T> handle) {
        readers.remove(handle);
    }

    public int getCapacity() {
        return capacity;
    }
}
