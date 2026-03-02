package ringbuffer;

/**
    ReaderHandle keeps track of where a single reader is in the RingBuffer.
    Each reader has its own read position, so readers do not affect each other while reading from the same buffer.
    If the writer overwrites data that this reader has not read yet, the reader simply skips those items. In that case, the read position
    is moved forward to the oldest available element and the skipped items are counted as missed.
    ReaderHandle objects are created through RingBuffer.registerReader().
 */
public class ReaderHandle<T> {

    private final RingBuffer<T> buffer;
    private final String name;

    // Absolute position showing how many items this reader has already read.
    private long readPos;

    // Number of items missed because the writer overwrote them.
    private long missedCount = 0;

    ReaderHandle(RingBuffer<T> buffer, long startPos, String name) {
        this.buffer = buffer;
        this.readPos = startPos;
        this.name = name;
    }

    public T read() throws InterruptedException {
        T item = buffer.readAt(readPos);
        readPos++;
        return item;
    }

    synchronized void onOverwrite(long overwrittenAbsIndex) {
        if (readPos <= overwrittenAbsIndex) {
            long skipped = overwrittenAbsIndex - readPos + 1;
            missedCount += skipped;
            readPos = overwrittenAbsIndex + 1;
        }
    }

    // Remove this reader from the buffer.
    public void unregister() {
        buffer.unregisterReader(this);
    }

    public String getName() {
        return name;
    }

    public synchronized long getMissedCount() {
        return missedCount;
    }

    public synchronized long getReadPos() {
        return readPos;
    }
}