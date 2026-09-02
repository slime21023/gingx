package com.example.actor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;

/** Per-activation reduction counter used by the Groovy AST transformation. */
public final class ReductionBudget {
    public static final java.lang.ScopedValue<ReductionBudget> CURRENT = java.lang.ScopedValue.newInstance();

    private final int refill;
    private final int mask;
    private final CancellationToken cancellation;
    private final Runnable preemptionObserver;
    private int remaining;

    public ReductionBudget(int refill, CancellationToken cancellation, Runnable preemptionObserver) {
        if (refill < 2 || Integer.bitCount(refill) != 1) {
            throw new IllegalArgumentException("refill must be a power of two >= 2");
        }
        this.refill = refill;
        this.mask = refill - 1;
        this.remaining = refill;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.preemptionObserver = Objects.requireNonNull(preemptionObserver, "preemptionObserver");
    }

    public void tick() {
        int next = remaining - 1;
        remaining = next;
        if ((next & mask) != 0) {
            return;
        }
        remaining = refill;
        preemptionObserver.run();
        cancellation.throwIfCancelled();
        Thread.yield();
        if (Thread.currentThread().isInterrupted() && cancellation.isCancelled()) {
            throw new CancellationException("Actor activation was interrupted");
        }
    }

    public void tick(int requestedRefill) {
        if (requestedRefill == refill) {
            tick();
            return;
        }
        int next = remaining - 1;
        int requestedMask = requestedRefill - 1;
        remaining = next;
        if ((next & requestedMask) != 0) {
            return;
        }
        remaining = refill;
        preemptionObserver.run();
        cancellation.throwIfCancelled();
        Thread.yield();
        if (Thread.currentThread().isInterrupted() && cancellation.isCancelled()) {
            throw new CancellationException("Actor activation was interrupted");
        }
    }

    public static void tickCurrent() {
        if (CURRENT.isBound()) {
            CURRENT.get().tick();
        }
    }

    public static void tickCurrent(int requestedRefill) {
        if (requestedRefill < 2 || Integer.bitCount(requestedRefill) != 1) {
            throw new IllegalArgumentException("requestedRefill must be a power of two >= 2");
        }
        if (CURRENT.isBound()) {
            CURRENT.get().tick(requestedRefill);
        }
    }

    public static <T> T with(ReductionBudget budget, Callable<T> operation) throws Exception {
        return java.lang.ScopedValue.where(CURRENT, budget).call(operation::call);
    }
}
