package com.example.actor;

import java.util.function.Supplier;

/** Internal-facing control surface used by supervision. */
public interface ManagedActorRef<M> extends ActorRef<M> {
    void restart();

    /** Keeps queued messages but stops activations from running until resumed. */
    void suspend();

    void resume();

    void setFailureListener(FailureListener listener);

    void addTerminationListener(TerminationListener listener);

    Supplier<? extends Actor<M>> actorFactory();
}
