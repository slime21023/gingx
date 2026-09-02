package com.example.actor.observability.micrometer;

import com.example.actor.ActorMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** Optional Micrometer bridge; the core remains free of metrics dependencies. */
public final class ActorMetricsBinder implements MeterBinder {
    private final ActorMetrics metrics;

    public ActorMetricsBinder(ActorMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        counter(registry, "actor.messages.accepted", "Accepted actor messages", ActorMetrics::acceptedCount);
        counter(registry, "actor.messages.full", "Mailbox-full rejections", ActorMetrics::fullCount);
        counter(registry, "actor.messages.dropped", "Dropped actor messages", ActorMetrics::droppedCount);
        counter(registry, "actor.messages.processed", "Processed actor messages", ActorMetrics::processedCount);
        counter(registry, "actor.failures", "Actor failures", ActorMetrics::failureCount);
        counter(registry, "actor.restarts", "Actor restarts", ActorMetrics::restartCount);
        counter(registry, "actor.preemptions", "Cooperative preemptions", ActorMetrics::preemptionCount);
        counter(registry, "actor.cancellations", "Actor cancellations", ActorMetrics::cancellationCount);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<ActorMetrics> value) {
        FunctionCounter.builder(name, metrics, value)
                .description(description)
                .register(registry);
    }
}

