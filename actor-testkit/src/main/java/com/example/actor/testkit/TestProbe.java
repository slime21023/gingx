package com.example.actor.testkit;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An actor whose mailbox a test can make assertions about.
 *
 * <p>Every expectation is deadline bounded and reports what actually arrived,
 * so a failing test says what went wrong instead of timing out silently. A
 * probe replaces the pattern of sleeping in a loop until some flag flips.</p>
 */
public final class TestProbe<M> implements AutoCloseable {
    /**
     * Large enough that a test failure is an assertion about content rather
     * than about the probe silently rejecting messages.
     */
    private static final int PROBE_MAILBOX_CAPACITY = 65_535;

    private final ActorRef<M> ref;
    private final BlockingQueue<M> received = new LinkedBlockingQueue<>();
    private final List<M> consumed = new ArrayList<>();
    private final String name;

    private TestProbe(ActorSystem system, String name) {
        this.name = name;
        this.ref = system.spawn(() -> new Actor<M>() {
            @Override
            protected void onMessage(M message, ActorContext<M> context) {
                received.add(message);
            }
        }, ActorOptions.builder().name(name).mailboxCapacity(PROBE_MAILBOX_CAPACITY).build());
    }

    public static <M> TestProbe<M> create(ActorSystem system) {
        return create(system, "probe");
    }

    public static <M> TestProbe<M> create(ActorSystem system, String name) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(name, "name");
        return new TestProbe<>(system, name);
    }

    /** The reference to hand to the code under test. */
    public ActorRef<M> ref() {
        return ref;
    }

    /** @throws AssertionError when no message arrives before the deadline */
    public M expectMessage(Duration timeout) {
        M message = poll(timeout);
        if (message == null) {
            throw new AssertionError(name + " expected a message within " + timeout
                    + " but received none. Already consumed: " + consumed);
        }
        return message;
    }

    public M expectMessage(M expected, Duration timeout) {
        M message = expectMessage(timeout);
        if (!Objects.equals(expected, message)) {
            throw new AssertionError(name + " expected " + expected + " but received " + message);
        }
        return message;
    }

    public <T extends M> T expectMessageOfType(Class<T> type, Duration timeout) {
        Objects.requireNonNull(type, "type");
        M message = expectMessage(timeout);
        if (!type.isInstance(message)) {
            throw new AssertionError(name + " expected a " + type.getSimpleName()
                    + " but received " + message.getClass().getSimpleName() + ": " + message);
        }
        return type.cast(message);
    }

    /** Collects exactly {@code count} messages, in arrival order. */
    public List<M> receiveN(int count, Duration timeout) {
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        long deadline = System.nanoTime() + timeout.toNanos();
        List<M> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long remaining = deadline - System.nanoTime();
            M message = remaining <= 0 ? null : poll(Duration.ofNanos(remaining));
            if (message == null) {
                throw new AssertionError(name + " expected " + count + " messages within " + timeout
                        + " but received " + messages.size() + ": " + messages);
            }
            messages.add(message);
        }
        return List.copyOf(messages);
    }

    /** @throws AssertionError when any message arrives during {@code duration} */
    public void expectNoMessage(Duration duration) {
        M message = poll(duration);
        if (message != null) {
            throw new AssertionError(name + " expected no message within " + duration
                    + " but received " + message);
        }
    }

    /** Messages taken by earlier expectations, oldest first. */
    public List<M> consumedMessages() {
        return List.copyOf(consumed);
    }

    @Override
    public void close() {
        ref.stop();
    }

    private M poll(Duration timeout) {
        try {
            M message = received.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (message != null) consumed.add(message);
            return message;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(name + " was interrupted while waiting for a message", interrupted);
        }
    }
}
