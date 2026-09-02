package com.example.actor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link ActorScheduler}: one daemon thread for the whole system.
 *
 * <p>A scheduled task only enqueues a message, so a single thread serves any
 * number of actors. It is a daemon thread because a pending timer must never
 * keep the JVM alive.</p>
 */
final class SystemScheduler implements ActorScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "actor-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Cancellable scheduleOnce(Duration delay, Runnable task) {
        return new FutureCancellable(executor.schedule(task, toNanos(delay), TimeUnit.NANOSECONDS));
    }

    @Override
    public Cancellable schedulePeriodically(Duration initialDelay, Duration interval, Runnable task) {
        long intervalNanos = toNanos(interval);
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return new FutureCancellable(executor.scheduleWithFixedDelay(
                task, toNanos(initialDelay), intervalNanos, TimeUnit.NANOSECONDS));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static long toNanos(Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return delay.toNanos();
    }

    private static final class FutureCancellable implements Cancellable {
        private final Future<?> future;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private FutureCancellable(Future<?> future) {
            this.future = future;
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            future.cancel(false);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
