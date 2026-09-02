package com.example.actor;

import java.time.Duration;
import java.util.Objects;

/** Immutable defaults and lifecycle policy for an {@link ActorSystem}. */
public final class ActorSystemOptions {
    private final ActorOptions defaultActorOptions;
    private final Duration shutdownTimeout;
    private final DeadLetterListener deadLetterListener;
    private final ActorScheduler scheduler;

    private ActorSystemOptions(Builder builder) {
        this.defaultActorOptions = builder.defaultActorOptions;
        this.shutdownTimeout = builder.shutdownTimeout;
        this.deadLetterListener = builder.deadLetterListener;
        this.scheduler = builder.scheduler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ActorSystemOptions defaults() {
        return builder().build();
    }

    public ActorOptions defaultActorOptions() {
        return defaultActorOptions;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    /** @return the dead letter listener, or null when undelivered messages are only counted */
    public DeadLetterListener deadLetterListener() {
        return deadLetterListener;
    }

    /** @return the caller-supplied scheduler, or null when the system owns a default one */
    public ActorScheduler scheduler() {
        return scheduler;
    }

    public static final class Builder {
        private ActorOptions defaultActorOptions = ActorOptions.defaults();
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        private DeadLetterListener deadLetterListener;
        private ActorScheduler scheduler;

        public Builder defaultActorOptions(ActorOptions options) {
            this.defaultActorOptions = Objects.requireNonNull(options, "defaultActorOptions");
            return this;
        }

        public Builder shutdownTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "shutdownTimeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("shutdownTimeout must be positive");
            }
            this.shutdownTimeout = timeout;
            return this;
        }

        /** Passing null keeps the default of counting undelivered messages only. */
        public Builder deadLetterListener(DeadLetterListener listener) {
            this.deadLetterListener = listener;
            return this;
        }

        /**
          * Supplies the time source for actor timers. A supplied scheduler is
          * owned by the caller and is not closed by the actor system; passing
          * null keeps the system-owned default.
          */
        public Builder scheduler(ActorScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public ActorSystemOptions build() {
            return new ActorSystemOptions(this);
        }
    }
}
