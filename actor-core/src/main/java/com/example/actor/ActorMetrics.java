package com.example.actor;

import java.util.concurrent.atomic.LongAdder;

public final class ActorMetrics {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder full = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder processed = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder restarts = new LongAdder();
    private final LongAdder preemptions = new LongAdder();
    private final LongAdder cancellations = new LongAdder();

    void accepted() { accepted.increment(); }
    void full() { full.increment(); }
    void dropped() { dropped.increment(); }
    void processed() { processed.increment(); }
    void failure() { failures.increment(); }
    void restart() { restarts.increment(); }
    void preemption() { preemptions.increment(); }
    void cancellation() { cancellations.increment(); }

    public long acceptedCount() { return accepted.sum(); }
    public long fullCount() { return full.sum(); }
    public long droppedCount() { return dropped.sum(); }
    public long processedCount() { return processed.sum(); }
    public long failureCount() { return failures.sum(); }
    public long restartCount() { return restarts.sum(); }
    public long preemptionCount() { return preemptions.sum(); }
    public long cancellationCount() { return cancellations.sum(); }

    public Snapshot snapshot() {
        return new Snapshot(acceptedCount(), fullCount(), droppedCount(), processedCount(),
                failureCount(), restartCount(), preemptionCount(), cancellationCount());
    }

    public record Snapshot(long accepted, long full, long dropped, long processed,
                           long failures, long restarts, long preemptions, long cancellations) {
    }
}
