package com.example.actor.testkit;

import com.example.actor.ActorScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * A scheduler on virtual time.
 *
 * <p>Nothing runs until {@link #advance(Duration)} moves the clock, so a test
 * for a five minute timeout takes no wall-clock time and never depends on
 * timing. Tasks run on the thread that calls {@code advance}; because a timer
 * task only enqueues a message, the actors then process it as usual, so pair
 * an advance with {@code ActorSystem.awaitQuiescent} before asserting.</p>
 */
public final class TestScheduler implements ActorScheduler {
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long nowNanos;
    private long sequence;

    /** Virtual time elapsed since this scheduler was created. */
    public synchronized Duration now() {
        return Duration.ofNanos(nowNanos);
    }

    /** Tasks that are scheduled and not yet cancelled. */
    public synchronized int pendingTaskCount() {
        return (int) queue.stream().filter(task -> !task.handle.isCancelled()).count();
    }

    /**
     * Moves virtual time forward, running every task that becomes due.
     *
     * @return the number of task executions performed
     */
    public int advance(Duration by) {
        Objects.requireNonNull(by, "by");
        if (by.isNegative()) throw new IllegalArgumentException("cannot move virtual time backwards");
        long target;
        synchronized (this) {
            target = nowNanos + by.toNanos();
        }
        int executions = 0;
        while (true) {
            Task due;
            synchronized (this) {
                due = queue.peek();
                if (due == null || due.dueNanos > target) {
                    nowNanos = target;
                    break;
                }
                queue.poll();
                nowNanos = due.dueNanos;
                if (due.handle.isCancelled()) continue;
                if (due.intervalNanos > 0) {
                    queue.add(due.repeatAt(nowNanos + due.intervalNanos, sequence++));
                }
            }
            // Run outside the lock: a task enqueues a message, which may in turn
            // schedule further work.
            due.task.run();
            executions++;
        }
        return executions;
    }

    @Override
    public Cancellable scheduleOnce(Duration delay, Runnable task) {
        return schedule(delay, 0L, task);
    }

    @Override
    public Cancellable schedulePeriodically(Duration initialDelay, Duration interval, Runnable task) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return schedule(initialDelay, interval.toNanos(), task);
    }

    private synchronized Cancellable schedule(Duration delay, long intervalNanos, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
        Handle handle = new Handle();
        queue.add(new Task(nowNanos + delay.toNanos(), sequence++, intervalNanos, task, handle));
        return handle;
    }

    /** Descriptions of the pending tasks, for diagnosing a stuck test. */
    public synchronized List<String> pendingTasks() {
        List<String> descriptions = new ArrayList<>();
        for (Task task : queue) {
            if (!task.handle.isCancelled()) {
                descriptions.add("due=" + Duration.ofNanos(task.dueNanos)
                        + (task.intervalNanos > 0 ? " every " + Duration.ofNanos(task.intervalNanos) : ""));
            }
        }
        return List.copyOf(descriptions);
    }

    /**
     * The cancellation handle a caller keeps. Every repeat of a periodic task
     * shares one handle, so cancelling stops the whole series and not just the
     * occurrence that happened to be queued.
     */
    private static final class Handle implements Cancellable {
        private volatile boolean cancelled;

        @Override
        public boolean cancel() {
            if (cancelled) return false;
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class Task implements Comparable<Task> {
        private final long dueNanos;
        private final long order;
        private final long intervalNanos;
        private final Runnable task;
        private final Handle handle;

        private Task(long dueNanos, long order, long intervalNanos, Runnable task, Handle handle) {
            this.dueNanos = dueNanos;
            this.order = order;
            this.intervalNanos = intervalNanos;
            this.task = task;
            this.handle = handle;
        }

        private Task repeatAt(long nextDue, long nextOrder) {
            return new Task(nextDue, nextOrder, intervalNanos, task, handle);
        }

        @Override
        public int compareTo(Task other) {
            int byTime = Long.compare(dueNanos, other.dueNanos);
            return byTime != 0 ? byTime : Long.compare(order, other.order);
        }
    }
}
