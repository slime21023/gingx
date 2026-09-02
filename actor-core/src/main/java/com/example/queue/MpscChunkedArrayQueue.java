package com.example.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single-consumer, multi-producer queue made from linked power-of-two chunks.
 *
 * <p>Producers reserve a monotonically increasing slot before publishing the
 * element with a release store. The consumer uses an acquire load and is the
 * only thread that advances the consumer index. A producer that is paused
 * after reservation can therefore delay the consumer, but it cannot corrupt or
 * reorder another producer's slot.</p>
 */
public final class MpscChunkedArrayQueue<E> {
    private static final int DEFAULT_CHUNK_SIZE = 1024;
    private static final VarHandle ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);

    private final int chunkSize;
    private final int chunkMask;
    private final Chunk<E> firstChunk;
    private final PaddedCounter producerIndex = new PaddedCounter();
    private final PaddedCounter consumerIndex = new PaddedCounter();
    private final AtomicReference<Chunk<E>> producerChunk;
    private volatile Chunk<E> consumerChunk;

    public MpscChunkedArrayQueue() {
        this(DEFAULT_CHUNK_SIZE);
    }

    public MpscChunkedArrayQueue(int chunkSize) {
        if (chunkSize < 2 || Integer.bitCount(chunkSize) != 1) {
            throw new IllegalArgumentException("chunkSize must be a power of two >= 2");
        }
        this.chunkSize = chunkSize;
        this.chunkMask = chunkSize - 1;
        Chunk<E> initial = new Chunk<>(0L, chunkSize);
        this.firstChunk = initial;
        this.producerChunk = new AtomicReference<>(initial);
        this.consumerChunk = initial;
    }

    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        long index = producerIndex.getAndIncrement();
        Chunk<E> chunk = chunkForProducer(index);
        int offset = (int) (index - chunk.baseIndex) & chunkMask;
        ARRAY_HANDLE.setRelease(chunk.elements, offset, element);
        return true;
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        long index = consumerIndex.get();
        long published = producerIndex.get();
        if (index >= published) {
            return null;
        }

        Chunk<E> chunk = consumerChunk;
        while (index >= chunk.baseIndex + chunkSize) {
            Chunk<E> next = chunk.next.getAcquire();
            if (next == null) {
                Thread.onSpinWait();
                continue;
            }
            chunk = next;
            consumerChunk = chunk;
        }

        int offset = (int) (index - chunk.baseIndex) & chunkMask;
        E element;
        while ((element = (E) ARRAY_HANDLE.getAcquire(chunk.elements, offset)) == null) {
            Thread.onSpinWait();
        }
        ARRAY_HANDLE.set(chunk.elements, offset, null);
        consumerIndex.lazySet(index + 1);
        return element;
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        long index = consumerIndex.get();
        if (index >= producerIndex.get()) {
            return null;
        }
        Chunk<E> chunk = consumerChunk;
        while (index >= chunk.baseIndex + chunkSize) {
            Chunk<E> next = chunk.next.getAcquire();
            if (next == null) {
                Thread.onSpinWait();
                continue;
            }
            chunk = next;
            consumerChunk = chunk;
        }
        int offset = (int) (index - chunk.baseIndex) & chunkMask;
        E element;
        while ((element = (E) ARRAY_HANDLE.getAcquire(chunk.elements, offset)) == null) {
            Thread.onSpinWait();
        }
        return element;
    }

    public int size() {
        long size = producerIndex.get() - consumerIndex.get();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, size));
    }

    public boolean isEmpty() {
        return producerIndex.get() == consumerIndex.get();
    }

    public void clear() {
        while (poll() != null) {
            // Drain the single-consumer queue.
        }
    }

    public long producerIndex() {
        return producerIndex.get();
    }

    public long consumerIndex() {
        return consumerIndex.get();
    }

    private Chunk<E> chunkForProducer(long index) {
        Chunk<E> chunk = producerChunk.get();
        // A producer may reserve a lower index but be delayed after another
        // producer has already advanced the shared hint to a later chunk.
        if (index < chunk.baseIndex) {
            chunk = firstChunk;
        }
        while (index >= chunk.baseIndex + chunkSize) {
            Chunk<E> next = chunk.next.getAcquire();
            if (next == null) {
                Chunk<E> candidate = new Chunk<>(chunk.baseIndex + chunkSize, chunkSize);
                if (chunk.next.compareAndSet(null, candidate)) {
                    next = candidate;
                } else {
                    next = chunk.next.getAcquire();
                }
            }
            chunk = next;
            producerChunk.compareAndSet(producerChunk.get(), chunk);
        }
        return chunk;
    }

    private static final class Chunk<E> {
        private final long baseIndex;
        private final Object[] elements;
        private final AtomicReference<Chunk<E>> next = new AtomicReference<>();

        private Chunk(long baseIndex, int size) {
            this.baseIndex = baseIndex;
            this.elements = new Object[size];
        }
    }

    /** Keeps the hot producer and consumer counters on separate cache lines. */
    private static final class PaddedCounter {
        @SuppressWarnings("unused")
        private long left01, left02, left03, left04, left05, left06, left07;
        private volatile long value;
        @SuppressWarnings("unused")
        private long right01, right02, right03, right04, right05, right06, right07;

        private static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(PaddedCounter.class, "value", long.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private long get() {
            return (long) VALUE.getAcquire(this);
        }

        private long getAndIncrement() {
            while (true) {
                long current = get();
                if (VALUE.compareAndSet(this, current, current + 1)) {
                    return current;
                }
            }
        }

        private void lazySet(long next) {
            VALUE.setRelease(this, next);
        }
    }
}
