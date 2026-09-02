package com.example.actor;

import java.time.Duration;

/**
 * Time source used for delayed and repeated actor work.
 *
 * <p>The runtime never blocks on a scheduler thread: a scheduled task only
 * enqueues a message and is subject to the normal mailbox capacity, so a task
 * must return promptly.</p>
 *
 * <p>Supplying an implementation makes time controllable, which is how timer
 * behaviour is tested without waiting for wall-clock delays.</p>
 */
public interface ActorScheduler {
    Cancellable scheduleOnce(Duration delay, Runnable task);

    Cancellable schedulePeriodically(Duration initialDelay, Duration interval, Runnable task);

    /** Releases scheduler resources. Only the owner of a scheduler closes it. */
    default void close() {
    }

    interface Cancellable {
        /** @return true when this call stopped a task that had not been cancelled yet */
        boolean cancel();

        boolean isCancelled();
    }
}
