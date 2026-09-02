package com.example.actor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The per-message view an actor has of the runtime.
 *
 * <p>A context is valid only while {@code onMessage} is executing. Do not
 * store it in actor state or use it from another thread.</p>
 */
public final class ActorContext<M> {
    public static final java.lang.ScopedValue<ActorContext<?>> CURRENT = java.lang.ScopedValue.newInstance();

    private final ActorCellView<M> cell;
    private final ActorSystem system;
    private final CancellationToken cancellation;
    private final TraceContext traceContext;
    private final CompletableFuture<Object> reply;
    private Object replyValue;
    private boolean replied;

    ActorContext(ActorCellView<M> cell, ActorSystem system, CancellationToken cancellation,
                 TraceContext traceContext, CompletableFuture<Object> reply) {
        this.cell = Objects.requireNonNull(cell, "cell");
        this.system = Objects.requireNonNull(system, "system");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.reply = reply;
    }

    public static ActorContext<?> current() {
        return CURRENT.get();
    }

    /** The typed reference of the actor currently handling a message. */
    public ActorRef<M> self() { return cell; }
    /** Keyed timers for this actor; see {@link Timers}. */
    public Timers<M> timers() { return cell; }

    /**
     * Defers the message being handled until {@link #unstashAll()}.
     *
     * <p>The usual shape is an actor that is not ready yet: it stashes work
     * until initialisation completes, then unstashes. A stashed message keeps
     * its place ahead of newer mail, and a stashed ask keeps its future open
     * until the message is finally handled.</p>
     *
     * <p>A stashed message no longer occupies mailbox capacity, so the stash
     * has its own bound: {@code ActorOptions.stashCapacity}.</p>
     *
     * @throws StashOverflowException when the stash is full
     */
    public void stash() {
        cell.stashCurrent();
    }

    /**
     * Re-delivers every stashed message, in the order it was stashed, ahead of
     * the messages still in the mailbox.
     *
     * <p>Re-delivery takes mailbox capacity again, so a message can be rejected
     * here; that is reported as a dead letter rather than silently dropped.
     * Messages are handed back after the current handler returns.</p>
     */
    public void unstashAll() {
        cell.requestUnstashAll();
    }

    /**
     * Creates an actor whose lifetime is contained in this one.
     *
     * <p>Stopping this actor stops its children, recursively, so a subtree does
     * not outlive the actor that created it. Termination is requested rather
     * than waited for: each child terminates on its own activation.</p>
     *
     * <p>Containment is a lifetime relationship, not a supervision policy. Use
     * {@code actor-supervision} for restart strategies.</p>
     */
    public <C> ActorRef<C> spawnChild(java.util.function.Supplier<? extends Actor<C>> factory,
                                      ActorOptions options) {
        return cell.spawnChild(factory, options);
    }

    /** Children created by this actor that have not terminated. */
    public int childCount() {
        return cell.childCount();
    }

    /** Messages currently deferred by {@link #stash()}. */
    public int stashSize() {
        return cell.stashSize();
    }

    public ActorSystem system() { return system; }
    public CancellationToken cancellation() { return cancellation; }
    public TraceContext traceContext() { return traceContext; }

    /**
     * Records the answer to the current ask request.
     *
     * <p>The future is completed by the runtime once the handler returns, not
     * here. Completing it inside the handler would run the caller's dependent
     * stages while this actor's {@link ActorContext} and {@link TraceContext}
     * are still bound, so a stage that sent a message would inherit this
     * actor's trace.</p>
     */
    public void reply(Object value) {
        if (reply == null) {
            throw new IllegalStateException("The current message is not an ask request");
        }
        this.replyValue = value;
        this.replied = true;
    }

    boolean replied() {
        return replied;
    }

    Object takeReply() {
        Object value = replyValue;
        replyValue = null;
        replied = false;
        return value;
    }
}
