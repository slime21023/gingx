package com.example.actor.testkit;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.ActorSystemOptions;
import com.example.actor.ManagedActorRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    void singleTimerFiresOnVirtualTime() {
        TestScheduler scheduler = new TestScheduler();
        try (ActorSystem system = newSystem(scheduler);
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> ref = timerActor(system, probe);

            ref.send("arm");
            ActorTestKit.awaitQuiescent(system, SETTLE);
            probe.expectNoMessage(Duration.ofMillis(50));
            assertEquals(1, scheduler.pendingTaskCount());

            ActorTestKit.advanceAndSettle(scheduler, system, Duration.ofMinutes(5), SETTLE);
            probe.expectMessage("tick", SETTLE);
            assertEquals(0, scheduler.pendingTaskCount(), "a single timer must not stay armed");
        }
    }

    @Test
    void cancelledTimerNeverFires() {
        TestScheduler scheduler = new TestScheduler();
        try (ActorSystem system = newSystem(scheduler);
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> ref = timerActor(system, probe);

            ref.send("arm");
            ref.send("disarm");
            ActorTestKit.awaitQuiescent(system, SETTLE);

            ActorTestKit.advanceAndSettle(scheduler, system, Duration.ofMinutes(10), SETTLE);
            probe.expectNoMessage(Duration.ofMillis(50));
        }
    }

    @Test
    void timerMessageAlreadyQueuedIsDiscardedAfterCancellation() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        ConcurrentLinkedQueue<Object> deadLetters = new ConcurrentLinkedQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ActorSystemOptions options = ActorSystemOptions.builder()
                .scheduler(scheduler)
                .deadLetterListener((target, message, reason) -> deadLetters.add(message))
                .build();

        try (ActorSystem system = new ActorSystem(options);
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> ref = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) throws Exception {
                    switch (message) {
                        case "arm" -> context.timers().startSingleTimer("t", "tick", Duration.ofMinutes(5));
                        case "hold" -> {
                            entered.countDown();
                            release.await();
                        }
                        case "disarm" -> context.timers().cancel("t");
                        default -> probe.ref().send(message);
                    }
                }
            }, ActorOptions.builder().mailboxCapacity(16).build());

            ref.send("arm");
            ref.send("hold");
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            // Queue the cancellation ahead of the timer message, then let the
            // timer fire while the actor is still blocked.
            ref.send("disarm");
            scheduler.advance(Duration.ofMinutes(5));
            release.countDown();

            ActorTestKit.awaitQuiescent(system, SETTLE);
            probe.expectNoMessage(Duration.ofMillis(50));
            assertTrue(deadLetters.contains("tick"),
                    "a stale timer message must be reported, saw " + deadLetters);
        }
    }

    @Test
    void periodicTimerFiresOncePerInterval() {
        TestScheduler scheduler = new TestScheduler();
        try (ActorSystem system = newSystem(scheduler);
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> ref = timerActor(system, probe);

            ref.send("arm-periodic");
            ActorTestKit.awaitQuiescent(system, SETTLE);

            ActorTestKit.advanceAndSettle(scheduler, system, Duration.ofMinutes(3), SETTLE);
            assertEquals(List.of("tick", "tick", "tick"), probe.receiveN(3, SETTLE));
            assertEquals(1, scheduler.pendingTaskCount(), "a periodic timer stays armed");
        }
    }

    @Test
    void restartCancelsTimersArmedByThePreviousInstance() {
        TestScheduler scheduler = new TestScheduler();
        try (ActorSystem system = newSystem(scheduler);
             TestProbe<String> probe = TestProbe.create(system)) {
            ManagedActorRef<String> ref = system.spawnManaged(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    if (message.equals("arm")) {
                        context.timers().startSingleTimer("t", "tick", Duration.ofMinutes(5));
                    } else {
                        probe.ref().send(message);
                    }
                }
            }, ActorOptions.defaults());

            ref.send("arm");
            ActorTestKit.awaitQuiescent(system, SETTLE);
            ref.restart();
            ActorTestKit.awaitQuiescent(system, SETTLE);

            ActorTestKit.advanceAndSettle(scheduler, system, Duration.ofMinutes(10), SETTLE);
            probe.expectNoMessage(Duration.ofMillis(50));
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

    private static ActorSystem newSystem(TestScheduler scheduler) {
        return new ActorSystem(ActorSystemOptions.builder().scheduler(scheduler).build());
    }

    private static ActorRef<String> timerActor(ActorSystem system, TestProbe<String> probe) {
        return system.spawn(() -> new Actor<String>() {
            @Override
            protected void onMessage(String message, ActorContext<String> context) {
                switch (message) {
                    case "arm" -> context.timers().startSingleTimer("t", "tick", Duration.ofMinutes(5));
                    case "arm-periodic" -> context.timers().startPeriodicTimer("t", "tick", Duration.ofMinutes(1));
                    case "disarm" -> context.timers().cancel("t");
                    default -> probe.ref().send(message);
                }
            }
        }, ActorOptions.defaults());
    }
}
