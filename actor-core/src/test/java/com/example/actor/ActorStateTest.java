package com.example.actor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActorStateTest {
    @Test
    void transitionsAndSchedulingAreSingleWinner() {
        ActorState state = new ActorState(2);
        state.initialize();
        assertEquals(ActorState.Lifecycle.IDLE, state.lifecycle());
        assertEquals(ActorState.EnqueueResult.SCHEDULE, state.reserveMessage());
        assertEquals(ActorState.EnqueueResult.ACCEPTED, state.reserveMessage());
        assertEquals(ActorState.EnqueueResult.FULL, state.reserveMessage());
        assertTrue(state.tryStart());
        assertFalse(state.tryStart());
        state.releaseMessage();
        state.releaseMessage();
        assertEquals(ActorState.Completion.IDLE, state.completeRun());
        assertEquals(ActorState.Lifecycle.IDLE, state.lifecycle());
    }

    @Test
    void restartKeepsMailboxCountAndClearsCancellation() {
        ActorState state = new ActorState(4);
        state.initialize();
        state.reserveMessage();
        state.cancel();
        state.fail();
        assertTrue(state.restart());
        assertFalse(state.isCancelled());
        assertEquals(1, state.mailboxCount());
        assertEquals(ActorState.Lifecycle.RUNNABLE, state.lifecycle());
    }
}
