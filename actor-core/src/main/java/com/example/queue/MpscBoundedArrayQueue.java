package com.example.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * A bounded multiple-producer, single-consumer queue backed by one fixed
 * power-of-two array.
 *
 * <p>The backing array is allocated once. No node, chunk or link is created
 * after construction, so a queue that has carried an arbitrary number of
 * messages retains exactly its initial footprint.</p>
 *
 * <p>Producers reserve a monotonically increasing slot before publishing the
 * element with a release store. The consumer uses an acquire load and is the
 * only thread that advances the consumer index. {@link #poll()} never spins: a
 * producer that is paused between its reservation and its publish is reported
 * as an empty result together with {@link #hasUnpublishedReservation()}, which
 * leaves the wait policy to the caller.</p>
 */
public final class MpscBoundedArrayQueue<E> {
    private static final int MAX_CAPACITY = 1 << 30;
    private static final VarHandle ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);

    private final int capacity;
    private final int mask;
    private final Object[] elements;
    private final PaddedCounter producerIndex = new PaddedCounter();
    private final PaddedCounter producerLimit = new PaddedCounter();
    private final PaddedCounter consumerIndex = new PaddedCounter();

    /** Rounds {@code requestedCapacity} up to the next power of two. */
    public MpscBoundedArrayQueue(int requestedCapacity) {
        if (requestedCapacity < 1 || requestedCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        int rounded = ceilingPowerOfTwo(requestedCapacity);
        this.capacity = rounded;
        this.mask = rounded - 1;
        this.elements = new Object[rounded];
        this.producerLimit.lazySet(rounded);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Reserves a slot and publishes {@code element}.
     *
     * @return false when the queue is full; no slot was reserved and nothing
     *         was written.
     */
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        long index;
        while (true) {
            index = producerIndex.get();
            if (index >= producerLimit.get()) {
                long limit = consumerIndex.get() + capacity;
                if (index >= limit) {
                    return false;
                }
                // A stale producer may publish an older limit here. The limit is
                // only ever a conservative hint, so a lost update costs one extra
                // consumer read and never admits a producer past the capacity.
                producerLimit.lazySet(limit);
            }
            if (producerIndex.compareAndSet(index, index + 1)) {
                break;
            }
        }
        ARRAY_HANDLE.setRelease(elements, (int) (index & mask), element);
        return true;
    }

    /**
     * @return the next element, or {@code null} when the queue is empty or when
     *         the next reserved slot has not been published yet
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        long index = consumerIndex.get();
        if (index >= producerIndex.get()) {
            return null;
        }
        int offset = (int) (index & mask);
        Object element = ARRAY_HANDLE.getAcquire(elements, offset);
        if (element == null) {
            return null;
        }
        // The slot is cleared before the consumer index is released, so a
        // producer that later passes the capacity check observes a free slot.
        ARRAY_HANDLE.set(elements, offset, null);
        consumerIndex.lazySet(index + 1);
        return (E) element;
    }

    /**
     * @return the next element without consuming it, or {@code null} when the
     *         queue is empty or the next reserved slot is unpublished
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        long index = consumerIndex.get();
        if (index >= producerIndex.get()) {
            return null;
        }
        return (E) ARRAY_HANDLE.getAcquire(elements, (int) (index & mask));
    }

    /** True when a producer reserved a slot that it has not published yet. */
    public boolean hasUnpublishedReservation() {
        long index = consumerIndex.get();
        return index < producerIndex.get()
                && ARRAY_HANDLE.getAcquire(elements, (int) (index & mask)) == null;
    }

    /** Includes reserved but unpublished slots. */
    public int size() {
        long size = producerIndex.get() - consumerIndex.get();
        return (int) Math.min(capacity, Math.max(0L, size));
    }

    public boolean isEmpty() {
        return producerIndex.get() == consumerIndex.get();
    }

    /** Removes every published element. Callable only by the consumer. */
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

    private static int ceilingPowerOfTwo(int value) {
        return value == 1 ? 1 : 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
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

        private boolean compareAndSet(long expected, long next) {
            return VALUE.compareAndSet(this, expected, next);
        }

        private void lazySet(long next) {
            VALUE.setRelease(this, next);
        }
    }
}
