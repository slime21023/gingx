package com.example.actor;

import java.util.concurrent.CancellationException;

public interface CancellationToken {
    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Actor activation was cancelled");
        }
    }
}
