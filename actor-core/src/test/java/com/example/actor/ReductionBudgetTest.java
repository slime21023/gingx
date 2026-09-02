package com.example.actor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReductionBudgetTest {
    @Test
    void checksCancellationOnlyAtReductionBoundary() {
        CancellationSource source = new CancellationSource();
        AtomicInteger preemptions = new AtomicInteger();
        ReductionBudget budget = new ReductionBudget(4, source.token(), preemptions::incrementAndGet);

        budget.tick();
        budget.tick();
        source.cancel();
        budget.tick();

        assertEquals(0, preemptions.get());
        assertThrows(CancellationException.class, budget::tick);
        assertEquals(1, preemptions.get());
    }
}
