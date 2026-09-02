package com.example.actor.supervisor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Small, thread-safe circuit breaker for protecting actor-backed dependencies. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long resetTimeoutNanos;
    private int failures;
    private long openedAt;
    private State state = State.CLOSED;

    public CircuitBreaker(int failureThreshold, Duration resetTimeout) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        Objects.requireNonNull(resetTimeout, "resetTimeout");
        if (resetTimeout.isZero() || resetTimeout.isNegative()) {
            throw new IllegalArgumentException("resetTimeout must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.resetTimeoutNanos = resetTimeout.toNanos();
    }

    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.HALF_OPEN) {
            return false;
        }
        if (System.nanoTime() - openedAt < resetTimeoutNanos) {
            return false;
        }
        state = State.HALF_OPEN;
        return true;
    }

    public synchronized void recordSuccess() {
        failures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN || ++failures >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.nanoTime();
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int consecutiveFailures() {
        return failures;
    }

    /** Executes an actor-backed asynchronous call and records its outcome. */
    public <T> CompletionStage<T> execute(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!allowRequest()) {
            return CompletableFuture.failedStage(new CircuitOpenException("Circuit breaker is open"));
        }
        try {
            CompletionStage<T> stage = Objects.requireNonNull(operation.get(), "operation returned null");
            return stage.whenComplete((value, failure) -> {
                if (failure == null) recordSuccess();
                else recordFailure();
            });
        } catch (Throwable failure) {
            recordFailure();
            return CompletableFuture.failedStage(failure);
        }
    }
}
