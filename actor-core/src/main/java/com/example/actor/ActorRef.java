package com.example.actor;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface ActorRef<M> {
    SendResult send(M message);

    CompletionStage<Object> ask(M message, Duration timeout);

    <R> CompletionStage<R> ask(M message, Duration timeout, Class<R> responseType);

    String name();

    void stop();

    void cancel();

    boolean isTerminated();

    void addTerminationListener(TerminationListener listener);

    void removeTerminationListener(TerminationListener listener);

    /** Groovy maps {@code ref << message} to this method. */
    default ActorRef<M> leftShift(M message) {
        send(message);
        return this;
    }
}
