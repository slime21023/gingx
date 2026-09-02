package com.example.actor.supervisor;

import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisionTest {
    @Test
    void circuitBreakerOpensAndAllowsOneHalfOpenProbe() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(20));
        breaker.recordFailure();
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertTrue(!breaker.allowRequest());
        Thread.sleep(30);
        assertTrue(breaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void oneForOneRecreatesActorAfterFailure() throws Exception {
        AtomicInteger instances = new AtomicInteger();
        CompletableFuture<Integer> recovered = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem(); Supervisor supervisor = new Supervisor(system, RestartStrategy.ONE_FOR_ONE)) {
            ActorRef<String> ref = supervisor.spawn(new ChildSpec<>("worker", () -> new Actor<>() {
                private final int instance = instances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    if (message.equals("fail") && instance == 1) {
                        throw new IllegalStateException("expected");
                    }
                    recovered.complete(instance);
                }
            }, ActorOptions.defaults()));

            ref.send("fail");
            for (int i = 0; i < 100 && instances.get() < 2; i++) {
                Thread.sleep(10);
            }
            ref.send("ok");
            assertEquals(2, recovered.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneForAllInterruptsActiveSiblingBeforeRestartingIt() throws Exception {
        AtomicInteger firstInstances = new AtomicInteger();
        AtomicInteger secondInstances = new AtomicInteger();
        CountDownLatch siblingStarted = new CountDownLatch(1);
        CompletableFuture<Integer> recovered = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem(); Supervisor supervisor = new Supervisor(system, RestartStrategy.ONE_FOR_ALL)) {
            ActorRef<String> first = supervisor.spawn(new ChildSpec<>("first", () -> new Actor<>() {
                private final int instance = firstInstances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    if (message.equals("fail") && instance == 1) {
                        throw new IllegalStateException("expected");
                    }
                }
            }, ActorOptions.defaults()));
            ActorRef<String> second = supervisor.spawn(new ChildSpec<>("second", () -> new Actor<>() {
                private final int instance = secondInstances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) throws Exception {
                    if (message.equals("hold")) {
                        siblingStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    } else if (message.equals("ok")) {
                        recovered.complete(instance);
                    }
                }
            }, ActorOptions.defaults()));

            second.send("hold");
            assertTrue(siblingStarted.await(5, TimeUnit.SECONDS));
            first.send("fail");
            for (int i = 0; i < 100 && secondInstances.get() < 2; i++) {
                Thread.sleep(10);
            }
            second.send("ok");
            assertEquals(2, recovered.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deathWatchSendsTerminatedSignal() throws Exception {
        CompletableFuture<Terminated> terminated = new CompletableFuture<>();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Terminated> observer = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Terminated message, ActorContext<Terminated> context) {
                    terminated.complete(message);
                }
            }, ActorOptions.defaults());
            ActorRef<String> target = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                }
            }, ActorOptions.defaults());
            DeathWatch watch = new DeathWatch();
            watch.watch(observer, target);
            target.stop();
            assertTrue(terminated.get(5, TimeUnit.SECONDS).actor() == target);
            watch.unwatch(observer, target);
        }
    }

    @Test
    void restForOneRestartsTheFailedChildAndItsLaterSiblings() throws Exception {
        AtomicInteger firstInstances = new AtomicInteger();
        AtomicInteger secondInstances = new AtomicInteger();
        AtomicInteger thirdInstances = new AtomicInteger();
        try (ActorSystem system = new ActorSystem();
             Supervisor supervisor = new Supervisor(system, RestartStrategy.REST_FOR_ONE)) {
            ActorRef<String> first = supervisor.spawn(new ChildSpec<>("first", () -> new Actor<>() {
                private final int instance = firstInstances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    if (message.equals("fail") && instance == 1) throw new IllegalStateException("expected");
                }
            }, ActorOptions.defaults()));
            ActorRef<String> second = supervisor.spawn(new ChildSpec<>("second", () -> new Actor<String>() {
                private final int ignored = secondInstances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                }
            }, ActorOptions.defaults()));
            ActorRef<String> third = supervisor.spawn(new ChildSpec<>("third", () -> new Actor<String>() {
                private final int ignored = thirdInstances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                }
            }, ActorOptions.defaults()));
            second.send("init");
            third.send("init");
            for (int i = 0; i < 100 && (secondInstances.get() < 1 || thirdInstances.get() < 1); i++) {
                Thread.sleep(10);
            }
            first.send("fail");
            for (int i = 0; i < 100 && (firstInstances.get() < 2 || secondInstances.get() < 2
                    || thirdInstances.get() < 2); i++) Thread.sleep(10);
            assertTrue(firstInstances.get() >= 2);
            assertTrue(secondInstances.get() >= 2);
            assertTrue(thirdInstances.get() >= 2);
        }
    }

    @Test
    void nestedSupervisorStopsOnlyItsOwnSubtreeOnCrashLoop() throws Exception {
        AtomicInteger instances = new AtomicInteger();
        try (ActorSystem system = new ActorSystem();
             Supervisor root = new Supervisor(system, RestartStrategy.ONE_FOR_ONE)) {
            Supervisor child = root.spawnSupervisor("child", RestartStrategy.ONE_FOR_ONE, 1, Duration.ofSeconds(1));
            ActorRef<String> ref = child.spawn(new ChildSpec<>("loop", () -> new Actor<String>() {
                private final int ignored = instances.incrementAndGet();

                @Override
                protected void onMessage(String message, ActorContext<String> context) {
                    throw new IllegalStateException("injected");
                }
            }, ActorOptions.defaults()));
            ref.send("first");
            for (int i = 0; i < 100 && instances.get() < 2; i++) Thread.sleep(10);
            ref.send("second");
            for (int i = 0; i < 100 && !ref.isTerminated(); i++) Thread.sleep(10);
            assertTrue(child.isStopped());
            assertTrue(ref.isTerminated());
            assertTrue(!root.isStopped());
        }
    }

    @Test
    void circuitBreakerGuardsAsyncActorCallsAndAllowsOneProbe() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(1));
        assertTrue(breaker.execute(() -> CompletableFuture.failedStage(new IllegalStateException("down")))
                .toCompletableFuture().isCompletedExceptionally());
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.execute(() -> CompletableFuture.completedFuture("blocked"))
                .toCompletableFuture().isCompletedExceptionally());
    }
}
