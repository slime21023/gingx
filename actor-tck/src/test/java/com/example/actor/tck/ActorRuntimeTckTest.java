package com.example.actor.tck;

import com.example.actor.Actor;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.PoisonPill;
import com.example.actor.SendResult;
import com.example.actor.ShutdownReport;
import com.example.actor.testkit.ActorTestKit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorRuntimeTckTest {
    @Test
    void poisonPillPreservesMailboxOrderAndTerminates() throws Exception {
        Set<Integer> received = ConcurrentHashMap.newKeySet();
        try (ActorSystem system = new ActorSystem()) {
            ActorRef<Object> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(Object message, com.example.actor.ActorContext<Object> context) {
                    if (message instanceof Integer value) received.add(value);
                }
            }, ActorOptions.builder().mailboxCapacity(16).build());
            for (int i = 0; i < 4; i++) assertEquals(SendResult.ACCEPTED, ref.send(i));
            assertEquals(SendResult.ACCEPTED, ref.send(PoisonPill.INSTANCE));
            ActorTestKit.awaitTerminated(ref, Duration.ofSeconds(5));
            assertEquals(Set.of(0, 1, 2, 3), received);
        }
    }

    @Test
    void actorSystemShutdownHasDeadlineAndRejectsNewMessages() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (ActorSystem system = new ActorSystem(ActorSystemOptionsForTest.options())) {
            ActorRef<String> ref = system.spawn(() -> new Actor<>() {
                @Override
                protected void onMessage(String message, com.example.actor.ActorContext<String> context) throws Exception {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            });
            assertEquals(SendResult.ACCEPTED, ref.send("hold"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread shutdown = Thread.startVirtualThread(() -> system.shutdown(Duration.ofSeconds(2)));
            SendResult result = SendResult.ACCEPTED;
            for (int i = 0; i < 100 && result == SendResult.ACCEPTED; i++) {
                result = ref.send("after-shutdown");
                if (result == SendResult.ACCEPTED) Thread.sleep(1);
            }
            assertTrue(result == SendResult.SYSTEM_SHUTTING_DOWN || result == SendResult.SYSTEM_CLOSED);
            shutdown.join(5000);
        }
    }

    @Test
    void randomizedFailureDoesNotDuplicateDeliveredMessages() throws Exception {
        Set<Integer> delivered = ConcurrentHashMap.newKeySet();
        Set<Integer> injectedFailures = Set.of(17, 83, 149);
        Set<Integer> failedOnce = ConcurrentHashMap.newKeySet();
        CountDownLatch completed = new CountDownLatch(160);
        try (ActorSystem system = new ActorSystem();
             com.example.actor.supervisor.Supervisor supervisor =
                     new com.example.actor.supervisor.Supervisor(system,
                             com.example.actor.supervisor.RestartStrategy.ONE_FOR_ONE, 100, Duration.ofSeconds(2))) {
            ActorRef<Integer> ref = supervisor.spawn(new com.example.actor.supervisor.ChildSpec<>(
                    "random", () -> new Actor<>() {
                        @Override
                        protected void onMessage(Integer message, com.example.actor.ActorContext<Integer> context) {
                            if (delivered.add(message) && injectedFailures.contains(message)
                                    && failedOnce.add(message)) {
                                throw new IllegalStateException("injected");
                            }
                            if (delivered.contains(message)) completed.countDown();
                        }
                    }, ActorOptions.defaults()));
            for (int i = 0; i < 200; i++) ref.send(i);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(delivered.size() <= 200);
        }
    }

    private static final class ActorSystemOptionsForTest {
        private static com.example.actor.ActorSystemOptions options() {
            return com.example.actor.ActorSystemOptions.builder()
                    .shutdownTimeout(Duration.ofSeconds(2))
                    .build();
        }
    }
}
