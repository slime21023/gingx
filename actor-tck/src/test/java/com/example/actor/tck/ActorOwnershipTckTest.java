package com.example.actor.tck;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.ActorSystemOptions;
import com.example.actor.DeadLetterListener;
import com.example.actor.ManagedActorRef;
import com.example.actor.SendResult;
import com.example.actor.ShutdownReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the invariant that exactly one thread consumes a mailbox,
 * and for the runtime remaining recoverable when a producer stalls between its
 * mailbox reservation and its publish.
 */
class ActorOwnershipTckTest {

    @Test
    void stalledReservationDoesNotPinACarrierOrBlockShutdown() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        ActorSystemOptions options = ActorSystemOptions.builder()
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        ShutdownReport report;
        try (ActorSystem system = new ActorSystem(options)) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) {
                    delivered.countDown();
                }
            }, ActorOptions.builder().mailboxCapacity(16).build());

            // Deliver one message so the mailbox and its state are initialised.
            assertEquals(SendResult.ACCEPTED, ref.send("warmup"));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));

            // Reproduce a producer that claimed a queue slot and never published
            // it by advancing the producer index without writing an element.
            stallOneQueueSlot(ref);
            // This message lands behind the gap, so the activation it schedules
            // finds an unpublished slot at the head of the mailbox.
            assertEquals(SendResult.ACCEPTED, ref.send("behind the gap"));

            // Prove the activation actually reached the gap: without the staged
            // back-off it would still be spinning inside poll() instead.
            for (int i = 0; i < 500 && system.metrics().reservationStallCount() == 0; i++) {
                Thread.sleep(10);
            }
            assertTrue(system.metrics().reservationStallCount() > 0,
                    "the activation must observe and abandon the unpublished reservation");

            // The activation gives the carrier back rather than spinning, so an
            // ordinary deadline-bounded shutdown still completes.
            report = system.shutdown(Duration.ofSeconds(5));
        }
        assertTrue(report.terminated(), "shutdown must complete despite the stalled reservation");
        assertEquals(0, report.remainingActors());
        assertTrue(report.elapsed().compareTo(Duration.ofSeconds(5)) < 0,
                "shutdown must not have to wait for its deadline");
    }

    @Test
    void concurrentRestartsNeverRunTwoActivationsAtOnce() throws Exception {
        AtomicBoolean insideHandler = new AtomicBoolean();
        AtomicBoolean overlapped = new AtomicBoolean();
        AtomicInteger processed = new AtomicInteger();

        try (ActorSystem system = new ActorSystem()) {
            ManagedActorRef<Integer> ref = system.spawnManaged(() -> new Actor<>() {
                @Override
                protected void onMessage(Integer message, ActorContext context) {
                    if (!insideHandler.compareAndSet(false, true)) {
                        overlapped.set(true);
                    }
                    processed.incrementAndGet();
                    insideHandler.set(false);
                }
            }, ActorOptions.builder().mailboxCapacity(64).build());

            // Hammer the window between tryStart() and the activation registering
            // its thread, which is where a foreign restart used to be able to
            // submit a second activation onto the same mailbox.
            Thread restarter = Thread.startVirtualThread(() -> {
                for (int i = 0; i < 2_000; i++) {
                    ref.restart();
                    Thread.onSpinWait();
                }
            });
            for (int i = 0; i < 2_000; i++) {
                ref.send(i);
            }
            restarter.join(30_000);

            for (int i = 0; i < 500 && processed.get() == 0; i++) Thread.sleep(10);
        }
        assertFalse(overlapped.get(), "two activations consumed the same mailbox");
        assertTrue(processed.get() > 0, "the actor must keep making progress across restarts");
    }

    @Test
    void concurrentStopNeverRacesTheTerminalDrain() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            AtomicBoolean insideHandler = new AtomicBoolean();
            AtomicBoolean overlapped = new AtomicBoolean();
            try (ActorSystem system = new ActorSystem()) {
                ActorRef<Integer> ref = system.spawn(() -> new Actor<>() {
                    @Override
                    protected void onMessage(Integer message, ActorContext context) {
                        if (!insideHandler.compareAndSet(false, true)) overlapped.set(true);
                        insideHandler.set(false);
                    }
                }, ActorOptions.builder().mailboxCapacity(64).build());

                for (int i = 0; i < 16; i++) ref.send(i);
                ref.stop();
                for (int i = 0; i < 200 && !ref.isTerminated(); i++) Thread.sleep(1);
                assertTrue(ref.isTerminated(), "stop must terminate the actor");
            }
            assertFalse(overlapped.get(), "the terminal drain overlapped a running activation");
        }
    }

    @Test
    void undeliveredMessagesReachTheDeadLetterListener() throws Exception {
        ConcurrentLinkedQueue<Object> deadLetters = new ConcurrentLinkedQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ActorSystemOptions options = ActorSystemOptions.builder()
                .deadLetterListener((target, message, reason) -> deadLetters.add(message))
                .build();

        try (ActorSystem system = new ActorSystem(options)) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) throws Exception {
                    entered.countDown();
                    release.await();
                }
            }, ActorOptions.builder().mailboxCapacity(2).build());

            assertEquals(SendResult.ACCEPTED, ref.send("hold"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(SendResult.ACCEPTED, ref.send("queued-1"));
            assertEquals(SendResult.ACCEPTED, ref.send("queued-2"));
            assertEquals(SendResult.FULL, ref.send("rejected"));
            release.countDown();
        }

        List<Object> observed = List.copyOf(deadLetters);
        assertTrue(observed.contains("rejected"),
                "a message rejected by a full mailbox must be reported: " + observed);
    }

    @Test
    void askContinuationDoesNotObserveTheReplyingActorContext() throws Exception {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext context) {
                    context.reply(message.toUpperCase());
                }
            }, ActorOptions.defaults());

            CompletionStage<Boolean> bound = ref.ask("hello", Duration.ofSeconds(5))
                    .thenApply(reply -> {
                        assertEquals("HELLO", reply);
                        return ActorContext.CURRENT.isBound();
                    });

            Boolean contextLeaked = bound.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(contextLeaked);
            assertFalse(contextLeaked,
                    "an ask continuation must not run inside the ActorContext binding of the replier");
        }
    }

    /**
     * Advances the mailbox producer index without publishing an element, which
     * is the state a producer leaves behind when it dies between its slot
     * reservation and its publish.
     */
    private static void stallOneQueueSlot(ActorRef<?> ref) throws Exception {
        Object cell = ref;
        Field mailboxField = cell.getClass().getDeclaredField("mailbox");
        mailboxField.setAccessible(true);
        Object mailbox = mailboxField.get(cell);
        assertNotNull(mailbox, "the mailbox must already be allocated");

        Field producerIndex = mailbox.getClass().getDeclaredField("producerIndex");
        producerIndex.setAccessible(true);
        Object counter = producerIndex.get(mailbox);
        Field value = counter.getClass().getDeclaredField("value");
        value.setAccessible(true);
        value.setLong(counter, value.getLong(counter) + 1);
    }
}
