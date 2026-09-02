package com.example.actor;

/** Base class for user Actor implementations. */
public abstract class Actor<M> {
    protected abstract void onMessage(M message, ActorContext context) throws Exception;
}
