package com.example.actor;

import com.example.queue.MpscChunkedArrayQueue;

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
        private final ActorSystem system;
        private final Supplier<? extends Actor<M>> factory;
        private final ActorOptions options;
        private final String name;
        private final ActorState state;
        private volatile MpscChunkedArrayQueue<Envelope<M>> mailbox;
        private final AtomicBoolean terminationNotified = new AtomicBoolean();
        private volatile Set<TerminationListener> terminationListeners;
        private volatile Actor<M> actor;
        private volatile CancellationSource cancellation;
        private volatile FailureListener failureListener;
        private volatile Thread activationThread;
        private volatile boolean restartRequested;

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

        private MpscChunkedArrayQueue<Envelope<M>> mailbox() {
            MpscChunkedArrayQueue<Envelope<M>> current = mailbox;
            if (current != null) return current;
            synchronized (this) {
                if (mailbox == null) mailbox = new MpscChunkedArrayQueue<>();
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
            if (system.isShuttingDown()) return SendResult.SYSTEM_SHUTTING_DOWN;
            if (system.lifecycle.get() == Lifecycle.CLOSED) return SendResult.SYSTEM_CLOSED;
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
                    return SendResult.DROPPED;
                }
                system.metrics.full();
                return SendResult.FULL;
            }
            if (reservation == ActorState.EnqueueResult.TERMINATED) return SendResult.TERMINATED;
            mailbox().offer(envelope);
            system.metrics.accepted();
            if (reservation == ActorState.EnqueueResult.SCHEDULE) system.schedule(this);
            return SendResult.ACCEPTED;
        }

        private SendResult enqueueWithDropOldest(Envelope<M> envelope) {
            ActorState.EnqueueResult reservation = state.reserveMessage();
            if (reservation == ActorState.EnqueueResult.FULL) {
                MpscChunkedArrayQueue<Envelope<M>> currentMailbox = mailbox();
                Envelope<M> removed = currentMailbox.poll();
                if (removed == null) {
                    system.metrics.full();
                    return SendResult.FULL;
                }
                state.releaseMessage();
                completeDropped(removed);
                reservation = state.reserveMessage();
                if (reservation == ActorState.EnqueueResult.FULL) {
                    system.metrics.full();
                    return SendResult.FULL;
                }
                currentMailbox.offer(envelope);
                system.metrics.dropped();
                return SendResult.ACCEPTED_AFTER_DROP;
            }
            if (reservation == ActorState.EnqueueResult.TERMINATED) return SendResult.TERMINATED;
            mailbox().offer(envelope);
            system.metrics.accepted();
            if (reservation == ActorState.EnqueueResult.SCHEDULE) system.schedule(this);
            return SendResult.ACCEPTED;
        }

        private void completeDropped(Envelope<M> envelope) {
            if (envelope.reply != null) {
                envelope.reply.completeExceptionally(new RejectedExecutionException("ask message dropped"));
            }
        }

        @Override
        public String name() { return name; }

        @Override
        public void stop() {
            state.requestStop();
            CancellationSource source = cancellation;
            if (source != null) source.cancel();
            Thread thread = activationThread;
            if (thread != null) {
                thread.interrupt();
            } else {
                terminateFromSystem();
            }
        }

        @Override
        public void cancel() {
            state.requestStop();
            CancellationSource source = cancellation;
            if (source != null) source.cancel();
            system.metrics.cancellation();
            Thread thread = activationThread;
            if (thread != null) {
                thread.interrupt();
            } else {
                terminateFromSystem();
            }
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
            synchronized (this) {
                if (isTerminated() || system.isClosed() || state.lifecycle() == ActorState.Lifecycle.STOPPING) return;
                if (activationThread != null) {
                    restartRequested = true;
                    CancellationSource source = cancellation;
                    if (source != null) source.cancel();
                    activationThread.interrupt();
                    return;
                }
                restartNow();
            }
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
                while (processed < options.maxBatch() && state.mailboxCount() > 0) {
                    activationCancellation.token().throwIfCancelled();
                    Envelope<M> envelope;
                    if (options.overflowStrategy() == MailboxOverflowStrategy.DROP_OLDEST) {
                        synchronized (this) {
                            envelope = mailbox == null ? null : mailbox.poll();
                            if (envelope != null) state.releaseMessage();
                        }
                    } else {
                        MpscChunkedArrayQueue<Envelope<M>> currentMailbox = mailbox;
                        envelope = currentMailbox == null ? null : currentMailbox.poll();
                        if (envelope != null) state.releaseMessage();
                    }
                    if (envelope == null) continue;
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
                        completeFailure(envelope, cancelled);
                        throw cancelled;
                    } catch (Throwable userFailure) {
                        completeFailure(envelope, userFailure);
                        throw userFailure;
                    } finally {
                        ActorEvents.endMessage(event, success);
                    }
                    processed++;
                }
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                boolean restarted = false;
                synchronized (this) {
                    activationThread = null;
                    if (failure == null && restartRequested && !system.isClosed()
                            && state.lifecycle() != ActorState.Lifecycle.STOPPING) {
                        restartNow();
                        restarted = true;
                    }
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

        /** Must be called with this monitor held and no active activation thread. */
        private void restartNow() {
            try {
                actor = Objects.requireNonNull(factory.get(), "actorFactory returned null");
                cancellation = new CancellationSource();
                restartRequested = false;
                system.metrics.restart();
                boolean schedule = state.restart();
                if (schedule) system.schedule(this);
            } catch (Throwable startupFailure) {
                restartRequested = false;
                terminate(new ActorCrashedException("Actor restart failed: " + name, startupFailure));
            }
        }

        private void handleFailure(Throwable failure) {
            system.metrics.failure();
            if (restartRequested && !system.isClosed() && state.lifecycle() != ActorState.Lifecycle.STOPPING) {
                synchronized (this) {
                    if (restartRequested) restartNow();
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
            synchronized (this) {
                if (!terminationNotified.compareAndSet(false, true)) return;
                state.requestStop();
                state.terminate();
                listeners = terminationListeners;
            }
            MpscChunkedArrayQueue<Envelope<M>> currentMailbox = mailbox;
            if (currentMailbox != null) {
                Envelope<M> pending;
                while ((pending = currentMailbox.poll()) != null) {
                    state.releaseMessageIfPresent();
                    completeFailure(pending, cause);
                }
            }
            system.remove(this);
            if (listeners != null) listeners.forEach(listener -> listener.onTerminated(this));
        }
    }
}
