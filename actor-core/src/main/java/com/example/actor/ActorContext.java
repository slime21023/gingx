package com.example.actor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ActorContext {
    public static final java.lang.ScopedValue<ActorContext> CURRENT = java.lang.ScopedValue.newInstance();

    private final ActorRef<?> self;
    private final ActorSystem system;
    private final CancellationToken cancellation;
    private final TraceContext traceContext;
    private final CompletableFuture<Object> reply;

    ActorContext(ActorRef<?> self, ActorSystem system, CancellationToken cancellation,
                 TraceContext traceContext, CompletableFuture<Object> reply) {
        this.self = Objects.requireNonNull(self, "self");
        this.system = Objects.requireNonNull(system, "system");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext");
        this.reply = reply;
    }

    public static ActorContext current() {
        return CURRENT.get();
    }

    public ActorRef<?> self() { return self; }
    public ActorSystem system() { return system; }
    public CancellationToken cancellation() { return cancellation; }
    public TraceContext traceContext() { return traceContext; }

    public void reply(Object value) {
        if (reply == null) {
            throw new IllegalStateException("The current message is not an ask request");
        }
        reply.complete(value);
    }
}
