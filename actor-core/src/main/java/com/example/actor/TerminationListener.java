package com.example.actor;

@FunctionalInterface
public interface TerminationListener {
    void onTerminated(ActorRef<?> actor);
}
