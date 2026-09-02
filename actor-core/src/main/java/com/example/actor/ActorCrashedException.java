package com.example.actor;

public final class ActorCrashedException extends RuntimeException {
    public ActorCrashedException(String message, Throwable cause) {
        super(message, cause);
    }
}
