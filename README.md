# Ring Buffer

A thread-safe ring buffer in Java supporting **one writer** and **multiple independent readers**, each with their own read position.


## Overview

A ring buffer (circular buffer) is a fixed-size array used as a queue. The writer continuously advances through the array, wrapping around when it reaches the end. This implementation extends the classic design to support multiple readers that each track their own position independently. Consequently, one slow reader does not block or affect any other reader.


## Design

### Classes and Responsibilities

| Class | Responsibility |
|---|---|
| `RingBuffer<T>` | Core data structure. Holds the circular array, write index, and sequence counters. Manages reader registration and overwrite notification. |
| `ReaderHandle<T>` | Represents one reader's state: its current absolute read position and how many items it has missed. Created via `RingBuffer.registerReader()`. |
| `Writer<T>` | Thin wrapper around `RingBuffer.write()`. Encapsulates the single-writer role and keeps producer code clean. |
| `Main` | Demo: one writer thread + three reader threads (fast/normal/slow). |

### Key Design Decisions

**Absolute indexing**: instead of a raw slot index (0…N-1), both the write position and each reader's position are _absolute_ counters (total items ever written/read). The actual slot is derived with `index % capacity`. This makes it trivial to compare positions and detect overwrite.

**Sequence numbers**: each slot carries a sequence counter incremented on every write. This allows future extension.

**Overwrite notification**: when the writer wraps around and overwrites a slot, it notifies every registered `ReaderHandle` via `onOverwrite()`. Any reader whose position falls on or before the overwritten index is fast-forwarded, and its `missedCount` is incremented.

---

## Multiple Readers

Each reader is registered with `RingBuffer.registerReader(name)`, which returns a `ReaderHandle`. The handle stores the reader's own `readPos` long. It is completely independent of every other reader. Readers never touch each other's state.

```
Buffer:  [ item-1 | item-2 | item-3 | item-4 | item-5 | item-6 | item-7 | item-8 ]
                     ^                   ^                            ^
                FastReader          NormalReader                  SlowReader
```

Three readers at three different positions. All are reading from the same underlying array without copying data.

---

## Overwriting

When the buffer is full (writer has written N items that haven't been read by someone) and the writer calls `write()` again:

1. The oldest slot is overwritten.
2. The writer calls `reader.onOverwrite(overwrittenIndex)` for every registered reader.
3. Any reader whose `readPos ≤ overwrittenIndex` has its `readPos` advanced to `overwrittenIndex + 1` and its `missedCount` incremented accordingly.
4. The reader continues reading from the new position. It simply misses the overwritten items.

---

## How to Compile and Run

### Prerequisites
- Java 11 or later
- No external dependencies

### Compile

```bash
# From the project root
javac -encoding UTF-8 -d out src/ringbuffer/*.java
```

### Run

```bash
java -cp out ringbuffer.Main
```

### Expected Output

```
[Writer]  wrote item-1  (writeIndex=1)
  [FastReader    ] read: item-1
  [NormalReader  ] read: item-1
  [SlowReader    ] read: item-1
[Writer]  wrote item-2  (writeIndex=2)
  [FastReader    ] read: item-2
...
  [SlowReader    ] done. missed=24

═══ Summary ═══════════════════════════════
  FastReader     read pos: 40    missed: 0
  NormalReader   read pos: 40    missed: 3
  SlowReader     read pos: 40    missed: 24
═══════════════════════════════════════════
```
