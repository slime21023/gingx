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

    private final ActorRef<M> self;
    private final Timers<M> timers;
    private final ActorSystem system;
    private final CancellationToken cancellation;
    private final TraceContext traceContext;
    private final CompletableFuture<Object> reply;
    private Object replyValue;
    private boolean replied;

    ActorContext(ActorRef<M> self, Timers<M> timers, ActorSystem system, CancellationToken cancellation,
                 TraceContext traceContext, CompletableFuture<Object> reply) {
        this.self = Objects.requireNonNull(self, "self");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.system = Objects.requireNonNull(system, "system");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.reply = reply;
    }

    public static ActorContext<?> current() {
        return CURRENT.get();
    }

    /** The typed reference of the actor currently handling a message. */
    public ActorRef<M> self() { return self; }
    /** Keyed timers for this actor; see {@link Timers}. */
    public Timers<M> timers() { return timers; }

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
