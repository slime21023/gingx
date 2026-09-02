package com.example.actor.tck;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.SendResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for the typed ask protocol. */
class ActorMessagingApiTckTest {
    private static final Duration SETTLE = Duration.ofSeconds(5);

    sealed interface Request permits GetLength, Ignore {
    }

    record GetLength(String value, ActorRef<Integer> replyTo) implements Request {
    }

    record Ignore(String value) implements Request {
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

}
