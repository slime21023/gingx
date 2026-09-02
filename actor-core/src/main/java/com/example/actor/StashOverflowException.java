package com.example.actor;

/**
 * Thrown by {@code ActorContext.stash()} when the stash is full.
 *
 * <p>The stash is bounded for the same reason the mailbox is: an unbounded
 * buffer of deferred messages is an out-of-memory failure waiting for the
 * right traffic. The exception surfaces inside the handler, so the actor fails
 * through the normal supervision path instead of growing silently.</p>
 */
public final class StashOverflowException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StashOverflowException(String message) {
        super(message);
    }
}
