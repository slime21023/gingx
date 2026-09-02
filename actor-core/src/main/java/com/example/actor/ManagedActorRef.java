package com.example.actor;

import java.util.function.Supplier;

/** Internal-facing control surface used by supervision. */
public interface ManagedActorRef<M> extends ActorRef<M> {
    void restart();

    void setFailureListener(FailureListener listener);

    void addTerminationListener(TerminationListener listener);

    Supplier<? extends Actor<M>> actorFactory();
}
