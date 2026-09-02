package com.example.actor.testkit;

import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Deadline-bounded waits for actor tests.
 *
 * <p>These replace sleeping in a loop until something happens. Where the
 * runtime can report an event they wait on it, and where a test has to observe
 * its own state the polling is at least centralised behind a deadline and a
 * failure message that says what was still pending.</p>
 */
public final class ActorTestKit {
    private static final Duration CONDITION_POLL_INTERVAL = Duration.ofMillis(1);

    private ActorTestKit() {
    }

    /**
     * Waits until no actor is running or holds queued messages.
     *
     * <p>Call it after the test has finished sending: quiescence then means
     * every message sent so far has been handled.</p>
     *
     * @throws AssertionError naming the actors that were still busy
     */
    public static void awaitQuiescent(ActorSystem system, Duration timeout) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(timeout, "timeout");
        if (system.awaitQuiescent(timeout)) return;
        throw new AssertionError("the actor system was still busy after " + timeout
                + ": " + system.busyActorNames());
    }

    /** Waits for a termination notification rather than polling for it. */
    public static void awaitTerminated(ActorRef<?> ref, Duration timeout) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(timeout, "timeout");
        CountDownLatch terminated = new CountDownLatch(1);
        // A listener added after termination is invoked immediately, so there
        // is no window between the check and the registration.
        ref.addTerminationListener(actor -> terminated.countDown());
        try {
            if (terminated.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + ref.name() + " to terminate");
        }
        throw new AssertionError(ref.name() + " did not terminate within " + timeout);
    }

    /**
     * Waits for a condition the test owns, such as a counter reaching a value.
     *
     * <p>Prefer a {@link TestProbe} or {@link #awaitQuiescent} where they fit:
     * they observe the runtime instead of sampling it.</p>
     */
    public static void awaitCondition(BooleanSupplier condition, Duration timeout, String description) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) return;
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was never met within " + timeout + ": " + description);
            }
            try {
                Thread.sleep(CONDITION_POLL_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + description);
            }
        }
    }

    /**
     * Runs due timers and then waits for the messages they produced to be
     * handled, which is the pairing a timer assertion almost always wants.
     */
    public static void advanceAndSettle(TestScheduler scheduler, ActorSystem system,
                                        Duration by, Duration timeout) {
        Objects.requireNonNull(scheduler, "scheduler");
        scheduler.advance(by);
        awaitQuiescent(system, timeout);
    }
}
