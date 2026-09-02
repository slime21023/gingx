package com.example.actor;

@FunctionalInterface
public interface FailureListener {
    void onFailure(ActorRef<?> actor, Throwable cause);
}
