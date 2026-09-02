package com.example.actor.tck;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.ActorSystemOptions;
import com.example.actor.SendResult;
import com.example.actor.testkit.ActorTestKit;
import com.example.actor.testkit.TestProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for the typed ask protocol and for message stashing. */
class ActorMessagingApiTckTest {
    private static final Duration SETTLE = Duration.ofSeconds(5);

    sealed interface Request permits GetLength, Ignore, Ready, Work {
    }

    record GetLength(String value, ActorRef<Integer> replyTo) implements Request {
    }

    record Ignore(String value) implements Request {
    }

    record Ready() implements Request {
    }

    record Work(String value) implements Request {
    }

    @Test
    void typedAskCarriesItsOwnReplyAddress() throws Exception {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    if (message instanceof GetLength request) {
                        request.replyTo().send(request.value().length());
                    }
                }
            }, ActorOptions.defaults());

            CompletionStage<Integer> inferredFromTarget =
                    ref.ask(Duration.ofSeconds(5), replyTo -> new GetLength("hello", replyTo));
            assertEquals(5, inferredFromTarget.toCompletableFuture().get(5, TimeUnit.SECONDS));

            // The witness overload keeps the reply type in a call chain.
            assertEquals(5, ref.ask(Integer.class, Duration.ofSeconds(5),
                            replyTo -> new GetLength("hello", replyTo))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void typedAskContinuationDoesNotObserveTheReplyingActor() throws Exception {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    if (message instanceof GetLength request) {
                        request.replyTo().send(request.value().length());
                    }
                }
            }, ActorOptions.defaults());

            Boolean leaked = ref.ask(Integer.class, Duration.ofSeconds(5),
                            replyTo -> new GetLength("abc", replyTo))
                    .thenApply(length -> ActorContext.CURRENT.isBound())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(leaked, "a reply must not complete inside the ActorContext binding of the replier");
        }
    }

    @Test
    void typedAskFailsOnTimeoutRatherThanWaitingForever() {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Request> silent = system.spawn(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                }
            }, ActorOptions.defaults());

            CompletionStage<Integer> answer =
                    silent.ask(Duration.ofMillis(100), replyTo -> new GetLength("x", replyTo));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> answer.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
        }
    }

    @Test
    void typedAskFailsWhenTheMailboxRejectsTheRequest() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) throws Exception {
                    entered.countDown();
                    release.await();
                }
            }, ActorOptions.builder().mailboxCapacity(1).build());

            assertEquals(SendResult.ACCEPTED, ref.send(new Ignore("hold")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(SendResult.ACCEPTED, ref.send(new Ignore("fills the mailbox")));

            CompletionStage<Integer> rejected =
                    ref.ask(Duration.ofSeconds(5), replyTo -> new GetLength("x", replyTo));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure.getCause());
            release.countDown();
        }
    }

    @Test
    void unstashedMessagesKeepTheirOrderAndPrecedeNewerMail() {
        try (ActorSystem system = new ActorSystem();
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                private boolean ready;

                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    if (message instanceof Ready) {
                        ready = true;
                        context.unstashAll();
                        return;
                    }
                    if (!ready) {
                        context.stash();
                        return;
                    }
                    probe.ref().send(((Work) message).value());
                }
            }, ActorOptions.builder().mailboxCapacity(32).build());

            ref.send(new Work("a"));
            ref.send(new Work("b"));
            ref.send(new Work("c"));
            ref.send(new Ready());
            ref.send(new Work("d"));

            assertEquals(List.of("a", "b", "c", "d"), probe.receiveN(4, SETTLE),
                    "stashed work must be redelivered in order and before later messages");
        }
    }

    @Test
    void stashedAskKeepsItsFutureOpenUntilTheMessageIsHandled() throws Exception {
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                private boolean ready;

                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    if (message instanceof Ready) {
                        ready = true;
                        context.unstashAll();
                        return;
                    }
                    if (!ready) {
                        context.stash();
                        return;
                    }
                    if (message instanceof GetLength request) {
                        request.replyTo().send(request.value().length());
                    }
                }
            }, ActorOptions.builder().mailboxCapacity(32).build());

            CompletionStage<Integer> answer =
                    ref.ask(Duration.ofSeconds(5), replyTo -> new GetLength("stashed", replyTo));
            ActorTestKit.awaitQuiescent(system, SETTLE);
            assertFalse(answer.toCompletableFuture().isDone(), "a stashed ask must stay open");

            ref.send(new Ready());
            assertEquals(7, answer.toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stashOverflowFailsTheActorInsteadOfGrowing() {
        ConcurrentLinkedQueue<Object> deadLetters = new ConcurrentLinkedQueue<>();
        ActorSystemOptions options = ActorSystemOptions.builder()
                .deadLetterListener((target, message, reason) -> deadLetters.add(message))
                .build();
        try (ActorSystem system = new ActorSystem(options)) {
            ActorRef<Request> ref = system.spawn(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    context.stash();
                }
            }, ActorOptions.builder().mailboxCapacity(16).stashCapacity(2).build());

            ref.send(new Work("1"));
            ref.send(new Work("2"));
            ref.send(new Work("3"));

            ActorTestKit.awaitTerminated(ref, SETTLE);
            assertTrue(ref.isTerminated(), "a full stash must fail the actor rather than grow");
            assertFalse(deadLetters.isEmpty(), "the deferred messages must be reported");
        }
    }

    @Test
    void restartDiscardsStashedMessages() {
        ConcurrentLinkedQueue<Object> deadLetters = new ConcurrentLinkedQueue<>();
        ActorSystemOptions options = ActorSystemOptions.builder()
                .deadLetterListener((target, message, reason) -> deadLetters.add(message))
                .build();
        try (ActorSystem system = new ActorSystem(options);
             TestProbe<String> probe = TestProbe.create(system)) {
            var ref = system.spawnManaged(() -> new Actor<Request>() {
                @Override
                protected void onMessage(Request message, ActorContext<Request> context) {
                    if (message instanceof Ready) {
                        context.unstashAll();
                        return;
                    }
                    context.stash();
                }
            }, ActorOptions.builder().mailboxCapacity(16).build());

            ref.send(new Work("kept-by-nobody"));
            ActorTestKit.awaitQuiescent(system, SETTLE);
            ref.restart();
            ActorTestKit.awaitQuiescent(system, SETTLE);

            ref.send(new Ready());
            ActorTestKit.awaitQuiescent(system, SETTLE);
            probe.expectNoMessage(Duration.ofMillis(50));
            assertTrue(deadLetters.stream().anyMatch(message -> message instanceof Work),
                    "stashed messages discarded by a restart must be reported: " + deadLetters);
        }
    }
}
