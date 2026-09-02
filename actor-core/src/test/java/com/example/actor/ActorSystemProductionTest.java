package com.example.actor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorSystemProductionTest {
    @Test
    void defaultSystemOptionsAreImmutableAndAppliedToSpawn() {
        ActorOptions actorOptions = ActorOptions.builder().name("defaulted").mailboxCapacity(8).build();
        ActorSystemOptions options = ActorSystemOptions.builder()
                .defaultActorOptions(actorOptions)
                .shutdownTimeout(Duration.ofSeconds(1))
                .build();
        try (ActorSystem system = new ActorSystem(options)) {
            assertEquals(actorOptions, system.options().defaultActorOptions());
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) {
                }
            });
            assertTrue(ref.name().startsWith("defaulted-"));
        }
    }

    @Test
    void shutdownStopsIdleActorsAndReturnsReport() {
        ActorSystem system = new ActorSystem();
        ActorRef<String> ref = system.spawn(() -> new Actor<>() {
            @Override
            protected void onMessage(String message, ActorContext context) {
            }
        });
        ShutdownReport report = system.shutdown(Duration.ofSeconds(2));
        assertTrue(report.terminated());
        assertEquals(0, report.remainingActors());
        assertEquals(SendResult.SYSTEM_CLOSED, ref.send("after"));
    }

    @Test
    void askRejectsWhenSystemIsShuttingDown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) throws Exception {
                    entered.countDown();
                    Thread.sleep(Duration.ofSeconds(5));
                }
            });
            ref.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread shutdown = Thread.startVirtualThread(() -> system.shutdown(Duration.ofSeconds(1)));
            SendResult result = SendResult.ACCEPTED;
            for (int i = 0; i < 100 && result == SendResult.ACCEPTED; i++) {
                result = ref.send("late");
                if (result == SendResult.ACCEPTED) Thread.sleep(1);
            }
            assertTrue(result == SendResult.SYSTEM_SHUTTING_DOWN || result == SendResult.SYSTEM_CLOSED);
            shutdown.join(3000);
        }
    }
}
