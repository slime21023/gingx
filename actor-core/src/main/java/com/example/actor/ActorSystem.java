package com.example.actor;

import com.example.queue.MpscBoundedArrayQueue;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A single-JVM actor runtime backed by Java virtual threads. */
public final class ActorSystem implements AutoCloseable {
    private enum Lifecycle { OPEN, SHUTTING_DOWN, CLOSED }

    private final ActorSystemOptions systemOptions;
    private final ExecutorService executor;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.OPEN);
    private final AtomicLong ids = new AtomicLong();
    private final Set<ActorCell<?>> actors = ConcurrentHashMap.newKeySet();
    private final ActorMetrics metrics = new ActorMetrics();
    private final Object terminationMonitor = new Object();

    public ActorSystem() {
        this(ActorSystemOptions.defaults());
    }

    public ActorSystem(ActorSystemOptions options) {
        this.systemOptions = Objects.requireNonNull(options, "options");
        this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    public ActorSystemOptions options() {
        return systemOptions;
    }

    public <M> ActorRef<M> spawn(Supplier<? extends Actor<M>> factory) {
        return spawn(factory, systemOptions.defaultActorOptions());
    }

    public <M> ActorRef<M> spawn(Supplier<? extends Actor<M>> factory, ActorOptions options) {
        return spawnManaged(factory, options);
    }

    public <M> ManagedActorRef<M> spawnManaged(Supplier<? extends Actor<M>> factory, ActorOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(options, "options");
        if (lifecycle.get() != Lifecycle.OPEN) {
            throw new IllegalStateException("ActorSystem is closed");
        }
        String name = options.name() + "-" + ids.incrementAndGet();
        ActorCell<M> cell = new ActorCell<>(this, factory, options, name);
        actors.add(cell);
        cell.initialize();
        if (lifecycle.get() != Lifecycle.OPEN) {
            cell.stop();
            throw new IllegalStateException("ActorSystem is shutting down");
        }
        return cell;
    }

    public ActorMetrics metrics() {
        return metrics;
    }

    public int actorCount() {
        return actors.size();
    }

    void schedule(ActorCell<?> cell) {
        if (lifecycle.get() != Lifecycle.OPEN) {
            cell.terminateFromSystem();
            return;
        }
        try {
            executor.submit(cell::runActivation);
        } catch (RejectedExecutionException rejected) {
            cell.terminateFromSystem();
        }
    }

    void deadLetter(ActorRef<?> target, Object message, DeadLetterListener.Reason reason) {
        DeadLetterListener listener = systemOptions.deadLetterListener();
        if (listener == null) return;
        try {
            listener.onDeadLetter(target, message, reason);
        } catch (RuntimeException listenerFailure) {
            // A dead letter listener must never break the send or shutdown path.
        }
    }

    void remove(ActorCell<?> cell) {
        actors.remove(cell);
        synchronized (terminationMonitor) {
            terminationMonitor.notifyAll();
        }
    }

    boolean isClosed() {
        return lifecycle.get() != Lifecycle.OPEN;
    }

    boolean isShuttingDown() {
        return lifecycle.get() == Lifecycle.SHUTTING_DOWN;
    }

    /**
     * Requests graceful termination and waits up to {@code timeout}. Active
     * handlers receive cancellation and interruption. A handler that ignores
     * both may remain in the returned report as a timed-out actor.
     */
    public ShutdownReport shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long started = System.nanoTime();
        Lifecycle previous = lifecycle.getAndUpdate(current -> current == Lifecycle.OPEN
                ? Lifecycle.SHUTTING_DOWN : current);
        if (previous == Lifecycle.CLOSED) {
            return new ShutdownReport(timeout, Duration.ofNanos(System.nanoTime() - started),
                    actors.size(), actors.isEmpty() && executor.isTerminated());
        }
        if (previous == Lifecycle.OPEN) {
            actors.forEach(ActorCell::stop);
        }

        long deadline = saturatingAdd(started, timeout.toNanos());
        awaitActors(deadline);
        executor.shutdown();
        awaitExecutor(deadline);
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }
        lifecycle.set(Lifecycle.CLOSED);
        synchronized (terminationMonitor) {
            terminationMonitor.notifyAll();
        }
        return new ShutdownReport(timeout, Duration.ofNanos(System.nanoTime() - started),
                actors.size(), actors.isEmpty() && executor.isTerminated());
    }

    @Override
    public void close() {
        shutdown(systemOptions.shutdownTimeout());
    }

    private void awaitActors(long deadline) {
        synchronized (terminationMonitor) {
            while (!actors.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                try {
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    terminationMonitor.wait(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void awaitExecutor(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        try {
            executor.awaitTermination(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class Envelope<M> {
        private final M message;
        private final CompletableFuture<Object> reply;
        private final TraceContext traceContext;

        private Envelope(M message, CompletableFuture<Object> reply, TraceContext traceContext) {
            this.message = message;
            this.reply = reply;
            this.traceContext = traceContext;
        }
    }

    private static final class ActorCell<M> implements ManagedActorRef<M> {
        /** Spins that cover a producer which is a few instructions behind. */
        private static final int GAP_SPINS = 64;
        /** Yields that release the carrier so a descheduled producer can run. */
        private static final int GAP_YIELDS = 16;

        private final ActorSystem system;
        private final Supplier<? extends Actor<M>> factory;
        private final ActorOptions options;
        private final String name;
        private final ActorState state;
        private volatile MpscBoundedArrayQueue<Envelope<M>> mailbox;
        private final AtomicBoolean terminationNotified = new AtomicBoolean();
        private volatile Set<TerminationListener> terminationListeners;
        private volatile Actor<M> actor;
        private volatile CancellationSource cancellation;
        private volatile FailureListener failureListener;
        private volatile Thread activationThread;
        private volatile boolean restartRequested;
        private Throwable pendingDrainCause;

        private ActorCell(ActorSystem system, Supplier<? extends Actor<M>> factory,
                          ActorOptions options, String name) {
            this.system = system;
            this.factory = factory;
            this.options = options;
            this.name = name;
            this.state = new ActorState(options.mailboxCapacity());
        }

        private void initialize() {
            state.initialize();
        }

        private MpscBoundedArrayQueue<Envelope<M>> mailbox() {
            MpscBoundedArrayQueue<Envelope<M>> current = mailbox;
            if (current != null) return current;
            synchronized (this) {
                if (mailbox == null) mailbox = new MpscBoundedArrayQueue<>(options.mailboxCapacity());
                return mailbox;
            }
        }

        private CancellationSource cancellationSource() {
            CancellationSource current = cancellation;
            if (current != null) return current;
            synchronized (this) {
                if (cancellation == null) cancellation = new CancellationSource();
                return cancellation;
            }
        }

        private Actor<M> actorInstance() {
            Actor<M> current = actor;
            if (current != null) return current;
            synchronized (this) {
                if (actor == null) actor = Objects.requireNonNull(factory.get(), "actorFactory returned null");
                return actor;
            }
        }

        @Override
        public SendResult send(M message) {
            return enqueue(message, null);
        }

        @Override
        public java.util.concurrent.CompletionStage<Object> ask(M message, Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            SendResult result = enqueue(message, future);
            if (result != SendResult.ACCEPTED && result != SendResult.ACCEPTED_AFTER_DROP) {
                future.completeExceptionally(new RejectedExecutionException("send rejected: " + result));
            } else {
                future.orTimeout(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            return future;
        }

        @Override
        public <R> java.util.concurrent.CompletionStage<R> ask(M message, Duration timeout, Class<R> responseType) {
            Objects.requireNonNull(responseType, "responseType");
            return ask(message, timeout).thenApply(responseType::cast);
        }

        private SendResult enqueue(M message, CompletableFuture<Object> reply) {
            Objects.requireNonNull(message, "message");
            if (system.isShuttingDown()) {
                system.deadLetter(this, message, DeadLetterListener.Reason.SYSTEM_CLOSED);
                return SendResult.SYSTEM_SHUTTING_DOWN;
            }
            if (system.lifecycle.get() == Lifecycle.CLOSED) {
                system.deadLetter(this, message, DeadLetterListener.Reason.SYSTEM_CLOSED);
                return SendResult.SYSTEM_CLOSED;
            }
            TraceContext trace = ActorContext.CURRENT.isBound()
                    ? ActorContext.CURRENT.get().traceContext() : TraceContext.current();
            Envelope<M> envelope = new Envelope<>(message, reply, trace);

            if (options.overflowStrategy() == MailboxOverflowStrategy.DROP_OLDEST) {
                synchronized (this) {
                    return enqueueWithDropOldest(envelope);
                }
            }
            ActorState.EnqueueResult reservation = state.reserveMessage();
            if (reservation == ActorState.EnqueueResult.FULL) {
                if (options.overflowStrategy() == MailboxOverflowStrategy.DROP_LATEST) {
                    system.metrics.dropped();
                    system.deadLetter(this, message, DeadLetterListener.Reason.DROPPED);
                    return SendResult.DROPPED;
                }
                system.metrics.full();
                system.deadLetter(this, message, DeadLetterListener.Reason.MAILBOX_FULL);
                return SendResult.FULL;
            }
            if (reservation == ActorState.EnqueueResult.TERMINATED) {
                system.deadLetter(this, message, DeadLetterListener.Reason.TERMINATED);
                return SendResult.TERMINATED;
            }
            if (!publish(envelope, reservation)) {
                system.metrics.full();
                system.deadLetter(this, message, DeadLetterListener.Reason.MAILBOX_FULL);
                return SendResult.FULL;
            }
            system.metrics.accepted();
            return SendResult.ACCEPTED;
        }

        /**
         * Publishes a reserved envelope and submits the activation that the
         * reservation won.
         *
         * <p>The ring is sized to the mailbox capacity that {@link ActorState}
         * already enforces, so a rejected offer is unreachable today. It is
         * handled so that a future sizing change degrades into backpressure
         * instead of a silent overwrite.</p>
         */
        private boolean publish(Envelope<M> envelope, ActorState.EnqueueResult reservation) {
            boolean offered = mailbox().offer(envelope);
            if (!offered) state.releaseMessage();
            // The reservation may already have claimed the schedule transition.
            // The activation has to run even with nothing to collect, otherwise
            // RUNNABLE/scheduled would never be cleared.
            if (reservation == ActorState.EnqueueResult.SCHEDULE) system.schedule(this);
            return offered;
        }

        private SendResult enqueueWithDropOldest(Envelope<M> envelope) {
            ActorState.EnqueueResult reservation = state.reserveMessage();
            if (reservation == ActorState.EnqueueResult.FULL) {
                MpscBoundedArrayQueue<Envelope<M>> currentMailbox = mailbox();
                Envelope<M> removed = currentMailbox.poll();
                if (removed == null) {
                    // Nothing to drop: the head slot is reserved but unpublished.
                    system.metrics.full();
                    system.deadLetter(this, envelope.message, DeadLetterListener.Reason.MAILBOX_FULL);
                    return SendResult.FULL;
                }
                state.releaseMessage();
                completeDropped(removed);
                reservation = state.reserveMessage();
                if (reservation == ActorState.EnqueueResult.FULL) {
                    system.metrics.full();
                    system.deadLetter(this, envelope.message, DeadLetterListener.Reason.MAILBOX_FULL);
                    return SendResult.FULL;
                }
                if (reservation == ActorState.EnqueueResult.TERMINATED) {
                    system.deadLetter(this, envelope.message, DeadLetterListener.Reason.TERMINATED);
                    return SendResult.TERMINATED;
                }
                if (!publish(envelope, reservation)) {
                    system.metrics.full();
                    system.deadLetter(this, envelope.message, DeadLetterListener.Reason.MAILBOX_FULL);
                    return SendResult.FULL;
                }
                system.metrics.dropped();
                return SendResult.ACCEPTED_AFTER_DROP;
            }
            if (reservation == ActorState.EnqueueResult.TERMINATED) {
                system.deadLetter(this, envelope.message, DeadLetterListener.Reason.TERMINATED);
                return SendResult.TERMINATED;
            }
            if (!publish(envelope, reservation)) {
                system.metrics.full();
                system.deadLetter(this, envelope.message, DeadLetterListener.Reason.MAILBOX_FULL);
                return SendResult.FULL;
            }
            system.metrics.accepted();
            return SendResult.ACCEPTED;
        }

        private void completeDropped(Envelope<M> envelope) {
            if (envelope.reply != null) {
                envelope.reply.completeExceptionally(new RejectedExecutionException("ask message dropped"));
            }
            system.deadLetter(this, envelope.message, DeadLetterListener.Reason.DROPPED);
        }

        @Override
        public String name() { return name; }

        @Override
        public void stop() {
            requestTermination();
        }

        @Override
        public void cancel() {
            system.metrics.cancellation();
            requestTermination();
        }

        /**
         * Requests termination without assuming that a null activation thread
         * means no activation is running.
         *
         * <p>An activation registers its thread only after {@code tryStart()}
         * has already moved the cell to {@code RUNNING}. Deciding on the
         * lifecycle that {@code requestStop()} observed closes that window: a
         * caller that sees {@code RUNNING} leaves termination to the
         * activation, which re-reads the lifecycle under this monitor and
         * terminates the cell itself.</p>
         */
        private void requestTermination() {
            ActorState.Lifecycle previous = state.requestStop();
            CancellationSource source = cancellation;
            if (source != null) source.cancel();
            Thread thread;
            synchronized (this) {
                thread = activationThread;
                if (thread == null && previous != ActorState.Lifecycle.RUNNING) {
                    terminateFromSystem();
                    return;
                }
            }
            if (thread != null) thread.interrupt();
        }

        @Override
        public void suspend() {
            state.suspend();
        }

        @Override
        public void resume() {
            if (state.resume()) system.schedule(this);
        }

        @Override
        public boolean isTerminated() {
            return state.lifecycle() == ActorState.Lifecycle.TERMINATED;
        }

        @Override
        public void addTerminationListener(TerminationListener listener) {
            Objects.requireNonNull(listener, "listener");
            synchronized (this) {
                if (!terminationNotified.get()) {
                    if (terminationListeners == null) terminationListeners = ConcurrentHashMap.newKeySet();
                    terminationListeners.add(listener);
                    return;
                }
            }
            listener.onTerminated(this);
        }

        @Override
        public void removeTerminationListener(TerminationListener listener) {
            Set<TerminationListener> listeners = terminationListeners;
            if (listeners != null) listeners.remove(listener);
        }

        @Override
        public void restart() {
            CancellationSource toCancel = null;
            Thread toInterrupt = null;
            synchronized (this) {
                if (isTerminated() || system.isClosed() || state.lifecycle() == ActorState.Lifecycle.STOPPING) return;
                if (state.lifecycle() == ActorState.Lifecycle.RUNNING) {
                    // An activation owns the cell, possibly one that has not
                    // registered its thread yet. It restarts on the way out
                    // instead of having a second activation submitted here.
                    restartRequested = true;
                    toCancel = cancellation;
                    toInterrupt = activationThread;
                } else {
                    restartNow(false);
                }
            }
            if (toCancel != null) toCancel.cancel();
            if (toInterrupt != null) toInterrupt.interrupt();
        }

        @Override
        public void setFailureListener(FailureListener listener) {
            this.failureListener = listener;
        }

        @Override
        public Supplier<? extends Actor<M>> actorFactory() {
            return factory;
        }

        private void runActivation() {
            if (!state.tryStart()) return;
            CancellationSource activationCancellation = cancellationSource();
            synchronized (this) {
                if (state.lifecycle() == ActorState.Lifecycle.STOPPING
                        || state.lifecycle() == ActorState.Lifecycle.TERMINATED) {
                    activationThread = null;
                    terminateFromSystem();
                    return;
                }
                activationThread = Thread.currentThread();
            }
            ReductionBudget budget = new ReductionBudget(options.reductionBudget(),
                    activationCancellation.token(), system.metrics::preemption);
            Throwable failure = null;
            try {
                int processed = 0;
                int gapAttempts = 0;
                while (processed < options.maxBatch()
                        && state.lifecycle() == ActorState.Lifecycle.RUNNING
                        && state.mailboxCount() > 0) {
                    activationCancellation.token().throwIfCancelled();
                    Envelope<M> envelope = pollMailbox();
                    if (envelope == null) {
                        // The mailbox count is non-zero but the head slot has not
                        // been published: a producer is inside its reservation
                        // gap. Back off in stages so that a producer which never
                        // publishes can neither pin this carrier nor defeat a
                        // shutdown that relies on cancellation and interruption.
                        if (++gapAttempts <= GAP_SPINS) {
                            Thread.onSpinWait();
                        } else if (gapAttempts <= GAP_SPINS + GAP_YIELDS) {
                            Thread.yield();
                        } else {
                            system.metrics.reservationStall();
                            break;
                        }
                        continue;
                    }
                    gapAttempts = 0;
                    if (envelope.message instanceof PoisonPill) {
                        if (envelope.reply != null) envelope.reply.complete(null);
                        terminate(new CancellationException("PoisonPill"));
                        return;
                    }
                    ActorContext context = new ActorContext(this, system, activationCancellation.token(),
                            envelope.traceContext, envelope.reply);
                    ActorEvents.MessageEvent event = ActorEvents.beginMessage(name,
                            envelope.message.getClass().getName());
                    boolean success = false;
                    try {
                        invoke(envelope, context, budget);
                        system.metrics.processed();
                        success = true;
                    } catch (CancellationException cancelled) {
                        completeReplyOrFail(envelope, context, cancelled);
                        throw cancelled;
                    } catch (Throwable userFailure) {
                        completeReplyOrFail(envelope, context, userFailure);
                        throw userFailure;
                    } finally {
                        ActorEvents.endMessage(event, success);
                    }
                    // Completed here, outside the ScopedValue binding, so that a
                    // dependent stage of the ask future cannot observe the
                    // ActorContext or inherit the TraceContext of this actor.
                    completeReply(envelope, context);
                    processed++;
                }
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                boolean restarted = false;
                Throwable deferredDrain;
                synchronized (this) {
                    activationThread = null;
                    deferredDrain = pendingDrainCause;
                    pendingDrainCause = null;
                    if (deferredDrain == null && failure == null && restartRequested && !system.isClosed()
                            && state.lifecycle() != ActorState.Lifecycle.STOPPING) {
                        restartNow(true);
                        restarted = true;
                    }
                }
                if (deferredDrain != null) {
                    // Another thread terminated this cell while it was running
                    // and left the mailbox to its owner.
                    drainMailbox(deferredDrain);
                    return;
                }
                if (restarted) return;
                if (failure != null) {
                    handleFailure(failure);
                } else {
                    ActorState.Completion completion = state.completeRun();
                    if (completion == ActorState.Completion.MORE_WORK) system.schedule(this);
                    else if (completion == ActorState.Completion.STOPPING) terminateFromSystem();
                }
            }
        }

        private Envelope<M> pollMailbox() {
            if (options.overflowStrategy() == MailboxOverflowStrategy.DROP_OLDEST) {
                synchronized (this) {
                    Envelope<M> envelope = mailbox == null ? null : mailbox.poll();
                    if (envelope != null) state.releaseMessage();
                    return envelope;
                }
            }
            MpscBoundedArrayQueue<Envelope<M>> currentMailbox = mailbox;
            Envelope<M> envelope = currentMailbox == null ? null : currentMailbox.poll();
            if (envelope != null) state.releaseMessage();
            return envelope;
        }

        private void completeReply(Envelope<M> envelope, ActorContext context) {
            if (envelope.reply != null && context.replied()) {
                envelope.reply.complete(context.takeReply());
            }
        }

        /** A handler that already answered keeps its answer even if it then fails. */
        private void completeReplyOrFail(Envelope<M> envelope, ActorContext context, Throwable failure) {
            if (envelope.reply == null) return;
            if (context.replied()) envelope.reply.complete(context.takeReply());
            else completeFailure(envelope, failure);
        }

        private void completeFailure(Envelope<M> envelope, Throwable failure) {
            if (envelope.reply != null) {
                envelope.reply.completeExceptionally(new ActorCrashedException("Actor failed: " + name, failure));
            }
        }

        private void invoke(Envelope<M> envelope, ActorContext context, ReductionBudget budget) throws Exception {
            java.lang.ScopedValue.where(ActorContext.CURRENT, context)
                    .where(ReductionBudget.CURRENT, budget)
                    .call(() -> {
                        actorInstance().onMessage(envelope.message, context);
                        return null;
                    });
        }

        /**
         * Must be called with this monitor held and no active activation thread.
         *
         * @param fromOwner true when the caller is the activation that is
         *                  leaving the cell, which is still {@code RUNNING}
         */
        private void restartNow(boolean fromOwner) {
            try {
                actor = Objects.requireNonNull(factory.get(), "actorFactory returned null");
                cancellation = new CancellationSource();
                restartRequested = false;
                system.metrics.restart();
                ActorState.RestartResult result = fromOwner ? state.restartFromOwner() : state.restart();
                if (result == ActorState.RestartResult.SCHEDULE) system.schedule(this);
            } catch (Throwable startupFailure) {
                restartRequested = false;
                terminate(new ActorCrashedException("Actor restart failed: " + name, startupFailure));
            }
        }

        private void handleFailure(Throwable failure) {
            system.metrics.failure();
            if (restartRequested && !system.isClosed() && state.lifecycle() != ActorState.Lifecycle.STOPPING) {
                synchronized (this) {
                    if (restartRequested) restartNow(true);
                }
                return;
            }
            if (failure instanceof CancellationException || state.lifecycle() == ActorState.Lifecycle.STOPPING
                    || system.isClosed()) {
                terminate(new ActorCrashedException("Actor cancelled: " + name, failure));
                return;
            }
            state.fail();
            FailureListener listener = failureListener;
            if (listener == null) {
                terminate(new ActorCrashedException("Actor failed: " + name, failure));
            } else {
                try {
                    listener.onFailure(this, failure);
                    // The listener may have chosen neither restart nor stop.
                    // Queued messages must not be stranded in an idle cell that
                    // has no pending activation.
                    if (state.scheduleIfIdleWithWork()) system.schedule(this);
                } catch (Throwable supervisorFailure) {
                    terminate(new ActorCrashedException("Failure listener failed: " + name, supervisorFailure));
                }
            }
        }

        private void terminateFromSystem() {
            terminate(new CancellationException("Actor system stopped"));
        }

        private void terminate(Throwable cause) {
            Set<TerminationListener> listeners;
            boolean drainHere;
            synchronized (this) {
                if (!terminationNotified.compareAndSet(false, true)) return;
                state.requestStop();
                state.terminate();
                listeners = terminationListeners;
                // Only the thread that owns the activation may consume the
                // single-consumer mailbox. When another thread terminates a
                // running cell, its owner drains the mailbox on the way out.
                drainHere = activationThread == null || activationThread == Thread.currentThread();
                if (!drainHere) pendingDrainCause = cause;
            }
            if (drainHere) drainMailbox(cause);
            system.remove(this);
            if (listeners != null) listeners.forEach(listener -> listener.onTerminated(this));
        }

        private void drainMailbox(Throwable cause) {
            MpscBoundedArrayQueue<Envelope<M>> currentMailbox = mailbox;
            if (currentMailbox == null) return;
            Envelope<M> pending;
            while ((pending = currentMailbox.poll()) != null) {
                state.releaseMessageIfPresent();
                completeFailure(pending, cause);
                system.deadLetter(this, pending.message, DeadLetterListener.Reason.TERMINATED);
            }
        }
    }
}
