package com.example.actor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * A reply address backed by a future rather than by an actor.
 *
 * <p>It lets a request carry a typed {@code ActorRef<R> replyTo}, so both
 * directions of an ask are checked at compile time and answering is an
 * ordinary send. It has no mailbox and no activation: the first send completes
 * the future and later sends are ignored, which matches a request that is
 * answered once.</p>
 */
final class PromiseRef<R> implements ActorRef<R> {
    private final CompletableFuture<R> future;
    private final ActorSystem system;
    private final String name;

    PromiseRef(CompletableFuture<R> future, ActorSystem system, String name) {
        this.future = future;
        this.system = system;
        this.name = name;
    }

    @Override
    public SendResult send(R message) {
        Objects.requireNonNull(message, "message");
        if (future.isDone()) return SendResult.TERMINATED;
        if (ActorContext.CURRENT.isBound()) {
            // Completing here would run the caller's dependent stages on this
            // actor's activation thread and inside its ScopedValue binding, so
            // hand the completion to the runtime executor instead.
            try {
                future.completeAsync(() -> message, system.replyExecutor());
                return SendResult.ACCEPTED;
            } catch (RejectedExecutionException shuttingDown) {
                // The system is closing; completing inline is better than
                // leaving the caller waiting for a reply that never lands.
                future.complete(message);
                return SendResult.ACCEPTED;
            }
        }
        future.complete(message);
        return SendResult.ACCEPTED;
    }

    @Override
    public CompletionStage<Object> ask(R message, Duration timeout) {
        throw new UnsupportedOperationException("a reply address cannot be asked");
    }

    @Override
    public <T> CompletionStage<T> ask(R message, Duration timeout, Class<T> responseType) {
        throw new UnsupportedOperationException("a reply address cannot be asked");
    }

    @Override
    public <T> CompletionStage<T> ask(Duration timeout, Function<ActorRef<T>, R> messageFactory) {
        throw new UnsupportedOperationException("a reply address cannot be asked");
    }

    @Override
    public <T> CompletionStage<T> ask(Class<T> responseType, Duration timeout,
                                      Function<ActorRef<T>, R> messageFactory) {
        throw new UnsupportedOperationException("a reply address cannot be asked");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void stop() {
        cancel();
    }

    @Override
    public void cancel() {
        future.completeExceptionally(new java.util.concurrent.CancellationException(
                "reply address " + name + " was cancelled"));
    }

    @Override
    public boolean isTerminated() {
        return future.isDone();
    }

    @Override
    public void addTerminationListener(TerminationListener listener) {
        Objects.requireNonNull(listener, "listener");
        future.whenComplete((value, failure) -> listener.onTerminated(this));
    }

    @Override
    public void removeTerminationListener(TerminationListener listener) {
        // A reply address completes once; its listeners fire once and are then done.
    }
}
