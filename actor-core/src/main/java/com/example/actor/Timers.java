package com.example.actor;

import java.time.Duration;

/**
 * Keyed timers scoped to one actor.
 *
 * <p>Timers are keyed rather than handle-based because resetting a timeout is
 * the common case: starting a timer under a key that is already in use
 * replaces it, so re-arming is idempotent. A message from the replaced timer
 * that is already on its way is discarded before it reaches the handler.</p>
 *
 * <p>A timer message is an ordinary message: it is subject to the mailbox
 * capacity and is reported as a dead letter when the mailbox rejects it.
 * Timers belong to the actor cell, not to the actor instance, and are
 * cancelled when the actor restarts or terminates.</p>
 *
 * <p>Only the actor itself may use its timers, from inside {@code onMessage}.</p>
 */
public interface Timers<M> {
    /** Sends {@code message} to this actor once, after {@code delay}. */
    void startSingleTimer(Object key, M message, Duration delay);

    /** Sends {@code message} to this actor repeatedly, first after {@code interval}. */
    void startPeriodicTimer(Object key, M message, Duration interval);

    void startPeriodicTimer(Object key, M message, Duration initialDelay, Duration interval);

    /** True while a timer under {@code key} still owes this actor a message. */
    boolean isTimerActive(Object key);

    /** @return true when this call cancelled an active timer */
    boolean cancel(Object key);

    void cancelAll();
}
