package com.example.actor.stress;

import com.example.actor.Actor;
import com.example.actor.ActorOptions;
import com.example.actor.ActorSystem;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorStressTest {
    @Test
    void concurrentProducersDoNotLoseMessages() throws Exception {
        int producerCount = 8;
        int messagesPerProducer = 2_000;
        AtomicInteger received = new AtomicInteger();
        CountDownLatch complete = new CountDownLatch(producerCount * messagesPerProducer);
        try (ActorSystem system = new ActorSystem(); var producers = Executors.newFixedThreadPool(producerCount)) {
            var actor = system.spawn(() -> new Actor<Integer>() {
                @Override
                protected void onMessage(Integer message, com.example.actor.ActorContext context) {
                    received.incrementAndGet();
                    complete.countDown();
                }
            }, ActorOptions.builder().mailboxCapacity(20_000).build());
            for (int p = 0; p < producerCount; p++) {
                producers.submit(() -> {
                    for (int i = 0; i < messagesPerProducer; i++) actor.send(i);
                    return null;
                });
            }
            producers.shutdown();
            assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(complete.await(10, TimeUnit.SECONDS));
            assertEquals(producerCount * messagesPerProducer, received.get());
        }
    }
}
