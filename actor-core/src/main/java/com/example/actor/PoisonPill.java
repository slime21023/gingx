package com.example.actor;

/** System message that stops an actor after all preceding mailbox messages. */
public final class PoisonPill {
    public static final PoisonPill INSTANCE = new PoisonPill();

    private PoisonPill() {
    }
}
