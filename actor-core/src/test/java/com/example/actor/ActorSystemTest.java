package com.example.actor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ActorSystemTest {
    @Test
    void processesMessagesSequentiallyAndSupportsAsk() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CompletableFuture<Void> processed = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    if (message == 5) processed.complete(null);
                    active.decrementAndGet();
                    if (context.traceContext() == TraceContext.EMPTY) {
                        // The default envelope context is intentionally stable.
                    }
                }
            }, ActorOptions.builder().name("counter").build());
            for (int i = 0; i <= 5; i++) assertEquals(SendResult.ACCEPTED, ref.send(i));
            processed.get(5, TimeUnit.SECONDS);
            assertEquals(1, maximum.get());
        }
    }

    @Test
    void askCanReplyThroughContext() throws Exception {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) {
                    context.reply(message.toUpperCase());
                }
            }, ActorOptions.defaults());
            Object response = ref.ask("hello", Duration.ofSeconds(2)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("HELLO", response);
        }
    }

    @Test
    void boundedMailboxReturnsFull() throws Exception {
        CompletableFuture<Void> release = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) throws Exception {
                    release.get(5, TimeUnit.SECONDS);
                }
            }, ActorOptions.builder().mailboxCapacity(1).build());
            assertEquals(SendResult.ACCEPTED, ref.send(1));
            assertEquals(SendResult.FULL, ref.send(2));
            release.complete(null);
        }
    }

    @Test
    void dropOldestKeepsNewestQueuedMessage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Integer> thirdReceived = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) throws Exception {
                    if (message == 1) {
                        started.countDown();
                        release.get(5, TimeUnit.SECONDS);
                    }
                    if (message == 3) thirdReceived.complete(message);
                }
            }, ActorOptions.builder().mailboxCapacity(1)
                    .overflowStrategy(MailboxOverflowStrategy.DROP_OLDEST).build());
            assertEquals(SendResult.ACCEPTED, ref.send(1));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(SendResult.ACCEPTED, ref.send(2));
            assertEquals(SendResult.ACCEPTED_AFTER_DROP, ref.send(3));
            release.complete(null);
            assertEquals(3, thirdReceived.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void dropLatestRejectsNewestQueuedMessage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Integer> secondReceived = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) throws Exception {
                    if (message == 1) {
                        started.countDown();
                        release.get(5, TimeUnit.SECONDS);
                    }
                    if (message == 2) secondReceived.complete(message);
                }
            }, ActorOptions.builder().mailboxCapacity(1)
                    .overflowStrategy(MailboxOverflowStrategy.DROP_LATEST).build());
            assertEquals(SendResult.ACCEPTED, ref.send(1));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(SendResult.ACCEPTED, ref.send(2));
            assertEquals(SendResult.DROPPED, ref.send(3));
            release.complete(null);
            assertEquals(2, secondReceived.get(5, TimeUnit.SECONDS));
            assertEquals(1, system.metrics().droppedCount());
        }
    }

    @Test
    void cancellationStopsAReductionAwareActivation() throws Exception {
        CompletableFuture<Void> entered = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) {
                    entered.complete(null);
                    while (true) {
                        ReductionBudget.tickCurrent();
                    }
                }
            }, ActorOptions.builder().reductionBudget(8).build());
            ref.send(1);
            entered.get(5, TimeUnit.SECONDS);
            ref.cancel();
            for (int i = 0; i < 100 && !ref.isTerminated(); i++) Thread.sleep(10);
            assertTrue(ref.isTerminated());
        }
    }

    @Test
    void cancellingAnIdleActorTerminatesIt() {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) {
                }
            }, ActorOptions.defaults());
            ref.cancel();
            assertTrue(ref.isTerminated());
            assertEquals(SendResult.TERMINATED, ref.send(1));
        }
    }

    @Test
    void traceContextIsCapturedAtSendTime() throws Exception {
        CompletableFuture<TraceContext> observed = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) {
                    observed.complete(context.traceContext());
                }
            }, ActorOptions.defaults());
            TraceContext expected = new TraceContext("trace-42");
            TraceContext.where(expected, () -> ref.send("message"));
            assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
        }
    }
}
