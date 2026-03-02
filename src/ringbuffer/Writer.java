package ringbuffer;

/**
    Writer encapsulates the single-writer role.
    Only one Writer should exist per RingBuffer. It provides a clean API for writing items and hides the buffer internals from producer code.
 */
public class Writer<T> {

    private final RingBuffer<T> buffer;

    public Writer(RingBuffer<T> buffer) {
        this.buffer = buffer;
    }

    // Write an item into the ring buffer.
    public void write(T item) {
        buffer.write(item);
    }
}
