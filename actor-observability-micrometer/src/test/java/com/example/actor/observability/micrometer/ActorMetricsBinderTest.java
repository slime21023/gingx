package com.example.actor.observability.micrometer;

import com.example.actor.ActorMetrics;
import com.example.actor.Actor;
import com.example.actor.ActorSystem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorMetricsBinderTest {
    @Test
    void exportsCoreCountersWithoutCoreMicrometerDependency() {
        try (ActorSystem system = new ActorSystem()) {
            ActorMetrics metrics = system.metrics();
            CountDownLatch processed = new CountDownLatch(1);
            system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, com.example.actor.ActorContext<String> context) {
                    processed.countDown();
                }
            }).send("message");
            try {
                if (!processed.await(5, TimeUnit.SECONDS)) throw new AssertionError("message was not processed");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new ActorMetricsBinder(metrics).bindTo(registry);
            assertEquals(1.0, registry.get("actor.messages.accepted").functionCounter().count());
            assertEquals(1.0, registry.get("actor.messages.processed").functionCounter().count());
        }
    }
}
