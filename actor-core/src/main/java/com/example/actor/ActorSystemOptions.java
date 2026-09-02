package com.example.actor;

import java.time.Duration;
import java.util.Objects;

/** Immutable defaults and lifecycle policy for an {@link ActorSystem}. */
public final class ActorSystemOptions {
    private final ActorOptions defaultActorOptions;
    private final Duration shutdownTimeout;
    private final DeadLetterListener deadLetterListener;

    private ActorSystemOptions(Builder builder) {
        this.defaultActorOptions = builder.defaultActorOptions;
        this.shutdownTimeout = builder.shutdownTimeout;
        this.deadLetterListener = builder.deadLetterListener;
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

    public static final class Builder {
        private ActorOptions defaultActorOptions = ActorOptions.defaults();
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        private DeadLetterListener deadLetterListener;

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

        public ActorSystemOptions build() {
            return new ActorSystemOptions(this);
        }
    }
}
