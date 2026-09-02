package com.example.actor.stress;

import com.example.actor.Actor;
import com.example.actor.ActorSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "runMillionActors", matches = "true")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class MillionActorMemoryTest {
    @Test
    void oneMillionIdleActorsStayBelowConfiguredHeapBudget() {
        try (ActorSystem system = new ActorSystem()) {
            List<Object> references = new ArrayList<>(1_000_000);
            for (int i = 0; i < 1_000_000; i++) {
                references.add(system.spawn(() -> new Actor<Object>() {
                    @Override
                    protected void onMessage(Object message, com.example.actor.ActorContext context) {
                    }
                }));
            }
            System.gc();
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            assertTrue(used < 500L * 1024 * 1024,
                    () -> "heap used=" + used + " bytes");
        }
    }
}

