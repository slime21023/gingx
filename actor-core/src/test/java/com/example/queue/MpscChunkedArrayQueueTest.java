package com.example.queue;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MpscChunkedArrayQueueTest {
    @Test
    void preservesAllMessagesAcrossProducers() throws Exception {
        MpscChunkedArrayQueue<Long> queue = new MpscChunkedArrayQueue<>(64);
        int producers = 4;
        int perProducer = 10_000;
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(producers)) {
            for (int producer = 0; producer < producers; producer++) {
                int id = producer;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        queue.offer((long) id * perProducer + i);
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Set<Long> seen = ConcurrentHashMap.newKeySet();
        Long value;
        while ((value = queue.poll()) != null) {
            assertTrue(seen.add(value), "duplicate value " + value);
        }
        assertEquals(producers * perProducer, seen.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void rejectsNullAndHandlesChunkBoundaries() {
        MpscChunkedArrayQueue<Integer> queue = new MpscChunkedArrayQueue<>(4);
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        for (int i = 0; i < 20; i++) queue.offer(i);
        for (int i = 0; i < 20; i++) assertEquals(i, queue.poll());
        assertNull(queue.poll());
    }
}
