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
        assertEquals(ActorState.RestartResult.SCHEDULE, state.restart());
        assertFalse(state.isCancelled());
        assertEquals(1, state.mailboxCount());
        assertEquals(ActorState.Lifecycle.RUNNABLE, state.lifecycle());
    }

    @Test
    void restartIsRefusedWhileAnActivationOwnsTheCell() {
        ActorState state = new ActorState(4);
        state.initialize();
        state.reserveMessage();
        assertTrue(state.tryStart());
        assertEquals(ActorState.Lifecycle.RUNNING, state.lifecycle());
        assertEquals(ActorState.RestartResult.REFUSED, state.restart(),
                "a running activation must observe the restart itself");
        assertEquals(ActorState.Lifecycle.RUNNING, state.lifecycle());
    }

    @Test
    void requestStopReportsTheLifecycleItObserved() {
        ActorState state = new ActorState(4);
        state.initialize();
        state.reserveMessage();
        assertTrue(state.tryStart());
        assertEquals(ActorState.Lifecycle.RUNNING, state.requestStop());
        assertEquals(ActorState.Lifecycle.STOPPING, state.requestStop());
    }

    @Test
    void idleCellWithQueuedMessagesCanBeReArmed() {
        ActorState state = new ActorState(4);
        state.initialize();
        state.reserveMessage();
        assertTrue(state.tryStart());
        state.fail();
        assertEquals(ActorState.Lifecycle.IDLE, state.lifecycle());
        assertEquals(1, state.mailboxCount());
        assertTrue(state.scheduleIfIdleWithWork());
        assertEquals(ActorState.Lifecycle.RUNNABLE, state.lifecycle());
        assertFalse(state.scheduleIfIdleWithWork(), "re-arming must be idempotent");
    }

    @Test
    void suspendedCellDoesNotStartUntilResumed() {
        ActorState state = new ActorState(4);
        state.initialize();
        assertEquals(ActorState.EnqueueResult.SCHEDULE, state.reserveMessage());
        state.suspend();
        assertFalse(state.tryStart(), "a suspended cell must not run");
        assertTrue(state.resume(), "resume must resubmit the activation it swallowed");
        assertTrue(state.tryStart());
    }
}
