package com.example.actor.tck;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.testkit.ActorTestKit;
import com.example.actor.testkit.TestProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for actors that create actors. */
class ActorHierarchyTckTest {
    private static final Duration SETTLE = Duration.ofSeconds(5);

    @Test
    void aChildHandlesMessagesLikeAnyOtherActor() throws Exception {
        CompletableFuture<ActorRef<String>> childRef = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem();
             TestProbe<String> probe = TestProbe.create(system)) {
            ActorRef<String> parent = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    childRef.complete(context.spawnChild(() -> new Actor<String>() {
                        @Override
                        protected void onMessage(String childMessage, ActorContext<String> childContext) {
                            probe.ref().send("child:" + childMessage);
                        }
                    }, ActorOptions.builder().name("child").build()));
                }
            }, ActorOptions.builder().name("parent").build());

            parent.send("spawn");
            ActorRef<String> child = childRef.get(5, TimeUnit.SECONDS);
            child.send("hello");
            probe.expectMessage("child:hello", SETTLE);
            assertTrue(child.name().startsWith("parent-"), "a child name records its parent: " + child.name());
        }
    }

    @Test
    void stoppingAParentStopsItsSubtree() throws Exception {
        CompletableFuture<ActorRef<String>> childRef = new CompletableFuture<>();
        CompletableFuture<ActorRef<String>> grandchildRef = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> parent = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    childRef.complete(context.spawnChild(() -> new Actor<String>() {
                        @Override
                        protected void onMessage(String childMessage, ActorContext<String> childContext) {
                            grandchildRef.complete(childContext.spawnChild(() -> new Actor<String>() {
                                @Override
                                protected void onMessage(String leaf, ActorContext<String> leafContext) {
                                }
                            }, ActorOptions.builder().name("leaf").build()));
                        }
                    }, ActorOptions.builder().name("child").build()));
                }
            }, ActorOptions.builder().name("parent").build());

            parent.send("spawn");
            ActorRef<String> child = childRef.get(5, TimeUnit.SECONDS);
            child.send("spawn");
            ActorRef<String> grandchild = grandchildRef.get(5, TimeUnit.SECONDS);
            ActorTestKit.awaitQuiescent(system, SETTLE);

            parent.stop();

            ActorTestKit.awaitTerminated(parent, SETTLE);
            ActorTestKit.awaitTerminated(child, SETTLE);
            ActorTestKit.awaitTerminated(grandchild, SETTLE);
        }
    }

    @Test
    void aTerminatedChildLeavesItsParent() throws Exception {
        CompletableFuture<ActorRef<String>> childRef = new CompletableFuture<>();
        CompletableFuture<Integer> countAfterStop = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<String> parent = system.spawn(() -> new Actor<String>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    if (message.equals("spawn")) {
                        childRef.complete(context.spawnChild(() -> new Actor<String>() {
                            @Override
                            protected void onMessage(String childMessage, ActorContext<String> childContext) {
                            }
                        }, ActorOptions.builder().name("child").build()));
                    } else {
                        countAfterStop.complete(context.childCount());
                    }
                }
            }, ActorOptions.builder().name("parent").build());

            parent.send("spawn");
            ActorRef<String> child = childRef.get(5, TimeUnit.SECONDS);
            child.stop();
            ActorTestKit.awaitTerminated(child, SETTLE);

            parent.send("count");
            assertEquals(0, countAfterStop.get(5, TimeUnit.SECONDS),
                    "a terminated child must not stay attached to its parent");
        }
    }
}
