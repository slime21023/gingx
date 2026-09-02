package com.example.queue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MpscBoundedArrayQueueTest {
    @Test
    void preservesAllMessagesAcrossProducers() throws Exception {
        int producers = 4;
        int perProducer = 10_000;
        MpscBoundedArrayQueue<Long> queue = new MpscBoundedArrayQueue<>(producers * perProducer);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(producers)) {
            for (int producer = 0; producer < producers; producer++) {
                int id = producer;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        assertTrue(queue.offer((long) id * perProducer + i));
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
    void rejectsNullAndPreservesOrderAcrossWraparound() {
        MpscBoundedArrayQueue<Integer> queue = new MpscBoundedArrayQueue<>(4);
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        for (int i = 0; i < 20; i++) {
            assertTrue(queue.offer(i));
            assertEquals(i, queue.poll());
        }
        assertNull(queue.poll());
    }

    @Test
    void capacityIsRoundedUpAndEnforced() {
        MpscBoundedArrayQueue<Integer> queue = new MpscBoundedArrayQueue<>(3);
        assertEquals(4, queue.capacity());
        for (int i = 0; i < 4; i++) assertTrue(queue.offer(i));
        assertFalse(queue.offer(99), "a full queue must reject instead of overwriting");
        assertEquals(0, queue.poll());
        assertTrue(queue.offer(99), "the consumed slot must become reusable");
    }

    @Test
    void storageIsConstantRegardlessOfMessageCount() throws Exception {
        MpscBoundedArrayQueue<String> queue = new MpscBoundedArrayQueue<>(1024);
        Field elements = MpscBoundedArrayQueue.class.getDeclaredField("elements");
        elements.setAccessible(true);
        Object[] before = (Object[]) elements.get(queue);

        for (int i = 0; i < 2_000_000; i++) {
            assertTrue(queue.offer("m"));
            assertNotNull(queue.poll());
        }

        Object[] after = (Object[]) elements.get(queue);
        assertSame(before, after, "the backing array must never be replaced or chained");
        assertEquals(1024, after.length);
        assertEquals(0, queue.size());
        for (Object slot : after) {
            assertNull(slot, "a consumed slot must not retain its element");
        }
    }

    @Test
    void unpublishedReservationIsReportedInsteadOfSpinning() throws Exception {
        MpscBoundedArrayQueue<String> queue = new MpscBoundedArrayQueue<>(8);
        // Reproduce a producer that reserved a slot and was stopped before its
        // publish. The consumer must report the gap and return, never block.
        reserveWithoutPublishing(queue);

        assertTrue(queue.hasUnpublishedReservation());
        assertNull(queue.poll(), "poll must not wait for an unpublished slot");
        assertNull(queue.peek());

        // The stalled producer eventually publishes into the slot it reserved.
        publishIntoReservedSlot(queue, "late");
        assertFalse(queue.hasUnpublishedReservation());
        assertEquals("late", queue.poll());
    }

    private static void reserveWithoutPublishing(MpscBoundedArrayQueue<?> queue) throws Exception {
        Field producerIndex = MpscBoundedArrayQueue.class.getDeclaredField("producerIndex");
        producerIndex.setAccessible(true);
        Object counter = producerIndex.get(queue);
        Field value = counter.getClass().getDeclaredField("value");
        value.setAccessible(true);
        value.setLong(counter, value.getLong(counter) + 1);
    }

    private static void publishIntoReservedSlot(MpscBoundedArrayQueue<?> queue, Object element)
            throws Exception {
        Field elements = MpscBoundedArrayQueue.class.getDeclaredField("elements");
        elements.setAccessible(true);
        ((Object[]) elements.get(queue))[(int) queue.consumerIndex()] = element;
    }
}
