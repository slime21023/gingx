package com.example.actor.testkit;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestKitTest {
    private static final Duration SETTLE = Duration.ofSeconds(5);

    @Test
    void probeMakesMailboxContentAssertable() {
        try (ActorSystem system = new ActorSystem();
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> upper = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    probe.ref().send(message.toUpperCase());
                }
            }, ActorOptions.defaults());

            upper.send("a");
            upper.send("b");
            assertEquals(List.of("A", "B"), probe.receiveN(2, SETTLE));
            probe.expectNoMessage(Duration.ofMillis(50));
        }
    }

    @Test
    void probeReportsWhatItActuallyReceived() {
        try (ActorSystem system = new ActorSystem();
             TestProbe<String> probe = TestProbe.create(system, "reporter")) {
            probe.ref().send("unexpected");
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> probe.expectMessage("expected", SETTLE));
            assertTrue(failure.getMessage().contains("unexpected"), failure.getMessage());
        }
    }

    @Test
    void awaitQuiescentReplacesSleepPolling() {
        AtomicInteger processed = new AtomicInteger();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> counter = system.spawn(() -> new Actor<Integer>() {
                @Override
                protected void onMessage(Integer message, ActorContext<Integer> context) {
                    processed.incrementAndGet();
                }
            }, ActorOptions.builder().mailboxCapacity(4096).build());

            for (int i = 0; i < 1_000; i++) counter.send(i);
            ActorTestKit.awaitQuiescent(system, SETTLE);
            assertEquals(1_000, processed.get());
        }
    }

    @Test
    void awaitQuiescentNamesTheBusyActor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> blocked = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) throws Exception {
                    entered.countDown();
                    release.await();
                }
            }, ActorOptions.builder().name("holder").build());

            blocked.send("hold");
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> ActorTestKit.awaitQuiescent(system, Duration.ofMillis(100)));
            assertTrue(failure.getMessage().contains("holder"), failure.getMessage());
            release.countDown();
        }
    }

    @Test
    void awaitTerminatedObservesTheNotification() {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> ref = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                }
            }, ActorOptions.defaults());

            ref.stop();
            ActorTestKit.awaitTerminated(ref, SETTLE);
            assertTrue(ref.isTerminated());
        }
    }

}
