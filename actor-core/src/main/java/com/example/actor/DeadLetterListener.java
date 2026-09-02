package com.example.actor;

/**
 * Receives messages the runtime could not deliver.
 *
 * <p>Counters record that a message was rejected; a dead letter listener
 * records which message it was, which is what an operator needs in order to
 * diagnose a rejection after the fact. The listener runs on the thread that
 * rejected the message and must not block it.</p>
 */
@FunctionalInterface
public interface DeadLetterListener {
    enum Reason {
        /** The mailbox was at capacity under a fail-fast strategy. */
        MAILBOX_FULL,
        /** An overflow strategy discarded the message. */
        DROPPED,
        /** The target actor was stopping, terminated or restarting away. */
        TERMINATED,
        /** The actor system was shutting down or already closed. */
        SYSTEM_CLOSED
    }

    void onDeadLetter(ActorRef<?> target, Object message, Reason reason);
}
