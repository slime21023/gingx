package com.example.actor;

import java.util.Objects;

public final class ActorOptions {
    private final String name;
    private final int mailboxCapacity;
    private final MailboxOverflowStrategy overflowStrategy;
    private final int maxBatch;
    private final int reductionBudget;

    private ActorOptions(Builder builder) {
        this.name = builder.name;
        this.mailboxCapacity = builder.mailboxCapacity;
        this.overflowStrategy = builder.overflowStrategy;
        this.maxBatch = builder.maxBatch;
        this.reductionBudget = builder.reductionBudget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ActorOptions defaults() {
        return builder().build();
    }

    public String name() { return name; }
    public int mailboxCapacity() { return mailboxCapacity; }
    public MailboxOverflowStrategy overflowStrategy() { return overflowStrategy; }
    public int maxBatch() { return maxBatch; }
    public int reductionBudget() { return reductionBudget; }

    public static final class Builder {
        private String name = "actor";
        private int mailboxCapacity = 1024;
        private MailboxOverflowStrategy overflowStrategy = MailboxOverflowStrategy.FAIL_FAST;
        private int maxBatch = 256;
        private int reductionBudget = 4096;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder mailboxCapacity(int capacity) {
            this.mailboxCapacity = capacity;
            return this;
        }

        public Builder overflowStrategy(MailboxOverflowStrategy strategy) {
            this.overflowStrategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        public Builder maxBatch(int maxBatch) {
            this.maxBatch = maxBatch;
            return this;
        }

        public Builder reductionBudget(int reductionBudget) {
            this.reductionBudget = reductionBudget;
            return this;
        }

        public ActorOptions build() {
            if (mailboxCapacity < 1 || mailboxCapacity > 65535) {
                throw new IllegalArgumentException("mailboxCapacity must be between 1 and 65535");
            }
            if (maxBatch < 1) {
                throw new IllegalArgumentException("maxBatch must be positive");
            }
            if (reductionBudget < 2 || Integer.bitCount(reductionBudget) != 1) {
                throw new IllegalArgumentException("reductionBudget must be a power of two >= 2");
            }
            return new ActorOptions(this);
        }
    }
}
