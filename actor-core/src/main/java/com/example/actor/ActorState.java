package com.example.actor;

import java.util.concurrent.atomic.AtomicLong;

/** Packed lifecycle, scheduling, cancellation, generation and mailbox count. */
public final class ActorState {
    public enum Lifecycle { NEW, IDLE, RUNNABLE, RUNNING, STOPPING, TERMINATED }

    public enum EnqueueResult { ACCEPTED, SCHEDULE, FULL, TERMINATED }

    public enum Completion { IDLE, MORE_WORK, STOPPING }

    /**
     * REFUSED means a concurrent activation or a termination owns the cell and
     * the caller must not submit an activation of its own.
     */
    public enum RestartResult { REFUSED, IDLE, SCHEDULE }

    private static final int LIFECYCLE_BITS = 3;
    private static final long LIFECYCLE_MASK = (1L << LIFECYCLE_BITS) - 1;
    private static final long SCHEDULED = 1L << 3;
    private static final long CANCELLED = 1L << 4;
    private static final long SUSPENDED = 1L << 5;
    private static final int COUNT_SHIFT = 8;
    private static final long COUNT_MASK = 0xFFFFL << COUNT_SHIFT;
    private static final int GENERATION_SHIFT = 24;
    private static final long GENERATION_MASK = 0xFFFFFFFFL << GENERATION_SHIFT;
    private static final int MAX_COUNT = 0xFFFF;
    private static final Lifecycle[] LIFECYCLES = Lifecycle.values();

    private final int mailboxCapacity;
    private final AtomicLong packed = new AtomicLong(pack(Lifecycle.NEW, false, false, false, 0, 0));

    public ActorState(int mailboxCapacity) {
        if (mailboxCapacity < 1 || mailboxCapacity > MAX_COUNT) {
            throw new IllegalArgumentException("mailboxCapacity must be between 1 and 65535");
        }
        this.mailboxCapacity = mailboxCapacity;
    }

    public void initialize() {
        update(current -> lifecycle(current) == Lifecycle.NEW
                ? pack(Lifecycle.IDLE, false, false, false, 0, generation(current))
                : current);
    }

    public EnqueueResult reserveMessage() {
        while (true) {
            long current = packed.get();
            Lifecycle life = lifecycle(current);
            if (life == Lifecycle.STOPPING || life == Lifecycle.TERMINATED) {
                return EnqueueResult.TERMINATED;
            }
            int count = count(current);
            if (count >= mailboxCapacity || count >= MAX_COUNT) {
                return EnqueueResult.FULL;
            }
            boolean schedule = life == Lifecycle.IDLE && !isScheduled(current);
            Lifecycle nextLife = schedule ? Lifecycle.RUNNABLE : life;
            long next = pack(nextLife, isScheduled(current) || schedule, isCancelled(current),
                    isSuspended(current), count + 1, generation(current));
            if (packed.compareAndSet(current, next)) {
                return schedule ? EnqueueResult.SCHEDULE : EnqueueResult.ACCEPTED;
            }
        }
    }

    public boolean tryStart() {
        while (true) {
            long current = packed.get();
            if (lifecycle(current) != Lifecycle.RUNNABLE || !isScheduled(current)
                    || isSuspended(current)) {
                return false;
            }
            long next = pack(Lifecycle.RUNNING, true, isCancelled(current), isSuspended(current),
                    count(current), generation(current));
            if (packed.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public void releaseMessage() {
        while (true) {
            long current = packed.get();
            int currentCount = count(current);
            if (currentCount == 0) {
                throw new IllegalStateException("mailbox count underflow");
            }
            long next = pack(lifecycle(current), isScheduled(current), isCancelled(current),
                    isSuspended(current), currentCount - 1, generation(current));
            if (packed.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * Best-effort decrement used while a cell is terminating concurrently
     * with an activation. Termination owns the final state and must not fail
     * merely because the activation already released the same reservation.
     */
    boolean releaseMessageIfPresent() {
        while (true) {
            long current = packed.get();
            int currentCount = count(current);
            if (currentCount == 0) return false;
            long next = pack(lifecycle(current), isScheduled(current), isCancelled(current),
                    isSuspended(current), currentCount - 1, generation(current));
            if (packed.compareAndSet(current, next)) return true;
        }
    }

    public Completion completeRun() {
        while (true) {
            long current = packed.get();
            Lifecycle life = lifecycle(current);
            if (life == Lifecycle.STOPPING || life == Lifecycle.TERMINATED) {
                return Completion.STOPPING;
            }
            if (count(current) > 0) {
                long next = pack(Lifecycle.RUNNABLE, true, isCancelled(current), isSuspended(current),
                        count(current), generation(current));
                if (packed.compareAndSet(current, next)) {
                    return Completion.MORE_WORK;
                }
            } else {
                long next = pack(Lifecycle.IDLE, false, isCancelled(current), isSuspended(current),
                        0, generation(current));
                if (packed.compareAndSet(current, next)) {
                    return Completion.IDLE;
                }
            }
        }
    }

    /**
     * Moves the actor to {@code STOPPING}.
     *
     * @return the lifecycle observed before the transition. A caller that sees
     *         {@code RUNNING} must leave termination to the activation that
     *         owns the cell, because that activation may not have registered
     *         its thread yet.
     */
    public Lifecycle requestStop() {
        while (true) {
            long current = packed.get();
            Lifecycle life = lifecycle(current);
            if (life == Lifecycle.TERMINATED || life == Lifecycle.STOPPING) {
                return life;
            }
            long next = pack(Lifecycle.STOPPING, isScheduled(current), true, isSuspended(current),
                    count(current), generation(current));
            if (packed.compareAndSet(current, next)) {
                return life;
            }
        }
    }

    public void terminate() {
        update(current -> pack(Lifecycle.TERMINATED, false, true, false, count(current), generation(current)));
    }

    /** Marks an activation as failed while retaining messages still in the mailbox. */
    public void fail() {
        while (true) {
            long current = packed.get();
            Lifecycle life = lifecycle(current);
            if (life == Lifecycle.TERMINATED) {
                return;
            }
            long next = pack(Lifecycle.IDLE, false, isCancelled(current), isSuspended(current),
                    count(current), generation(current) + 1);
            if (packed.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * Starts a new generation and re-arms the cell when messages are queued.
     *
     * <p>{@code RUNNING} is refused: an activation owns the cell and must
     * observe the restart request itself, otherwise a second activation would
     * be submitted alongside it. The owner uses {@link #restartFromOwner()}.</p>
     */
    public RestartResult restart() {
        return restart(false);
    }

    /**
     * Restart performed by the activation that owns the cell.
     *
     * <p>{@code RUNNING} is accepted here because the caller is the owner and
     * is on its way out. Keeping the cell in {@code RUNNING} until this single
     * transition means no other activation can start in between, which a
     * release-then-restart sequence would allow.</p>
     */
    public RestartResult restartFromOwner() {
        return restart(true);
    }

    private RestartResult restart(boolean fromOwner) {
        while (true) {
            long current = packed.get();
            Lifecycle life = lifecycle(current);
            if (life == Lifecycle.TERMINATED || life == Lifecycle.STOPPING) {
                return RestartResult.REFUSED;
            }
            if (life == Lifecycle.RUNNING && !fromOwner) {
                return RestartResult.REFUSED;
            }
            boolean schedule = count(current) > 0;
            long next = pack(schedule ? Lifecycle.RUNNABLE : Lifecycle.IDLE, schedule, false,
                    false, count(current), generation(current) + 1);
            if (packed.compareAndSet(current, next)) {
                return schedule ? RestartResult.SCHEDULE : RestartResult.IDLE;
            }
        }
    }

    /**
     * Re-arms an idle cell that still holds queued messages, so that messages
     * left behind by a failure are not stranded without a pending activation.
     *
     * @return true when an activation must be submitted
     */
    public boolean scheduleIfIdleWithWork() {
        while (true) {
            long current = packed.get();
            if (lifecycle(current) != Lifecycle.IDLE || isScheduled(current) || count(current) == 0) {
                return false;
            }
            long next = pack(Lifecycle.RUNNABLE, true, isCancelled(current), isSuspended(current),
                    count(current), generation(current));
            if (packed.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public void cancel() {
        update(current -> current | CANCELLED);
    }

    public void suspend() {
        update(current -> current | SUSPENDED);
    }

    /**
     * Clears the suspended flag.
     *
     * <p>A suspended cell keeps its {@code scheduled} flag while
     * {@link #tryStart()} refuses to run it, so the activation that was
     * consumed while suspended has to be resubmitted here.</p>
     *
     * @return true when an activation must be submitted
     */
    public boolean resume() {
        while (true) {
            long current = packed.get();
            if (!isSuspended(current)) {
                return false;
            }
            long cleared = current & ~SUSPENDED;
            boolean needsRun = lifecycle(cleared) == Lifecycle.RUNNABLE && count(cleared) > 0;
            if (packed.compareAndSet(current, cleared)) {
                return needsRun;
            }
        }
    }

    public boolean isCancelled() {
        return isCancelled(packed.get());
    }

    public boolean isScheduled() {
        return isScheduled(packed.get());
    }

    public int mailboxCount() {
        return count(packed.get());
    }

    public Lifecycle lifecycle() {
        return lifecycle(packed.get());
    }

    public long rawState() {
        return packed.get();
    }

    private void update(java.util.function.LongUnaryOperator operation) {
        while (true) {
            long current = packed.get();
            long next = operation.applyAsLong(current);
            if (current == next || packed.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private static long pack(Lifecycle lifecycle, boolean scheduled, boolean cancelled, boolean suspended,
                             int count, long generation) {
        long value = lifecycle.ordinal();
        if (scheduled) value |= SCHEDULED;
        if (cancelled) value |= CANCELLED;
        if (suspended) value |= SUSPENDED;
        value |= ((long) count << COUNT_SHIFT) & COUNT_MASK;
        value |= (generation << GENERATION_SHIFT) & GENERATION_MASK;
        return value;
    }

    private static Lifecycle lifecycle(long value) {
        return LIFECYCLES[(int) (value & LIFECYCLE_MASK)];
    }

    private static boolean isScheduled(long value) { return (value & SCHEDULED) != 0; }
    private static boolean isCancelled(long value) { return (value & CANCELLED) != 0; }
    private static boolean isSuspended(long value) { return (value & SUSPENDED) != 0; }
    private static int count(long value) { return (int) ((value & COUNT_MASK) >>> COUNT_SHIFT); }
    private static long generation(long value) { return (value & GENERATION_MASK) >>> GENERATION_SHIFT; }
}
