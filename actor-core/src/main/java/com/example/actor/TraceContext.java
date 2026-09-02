package com.example.actor;

import java.util.Objects;
import java.util.concurrent.Callable;

public record TraceContext(String traceId) {
    public static final TraceContext EMPTY = new TraceContext("");

    public static final java.lang.ScopedValue<TraceContext> CURRENT = java.lang.ScopedValue.newInstance();

    public TraceContext {
        Objects.requireNonNull(traceId, "traceId");
    }

    public static TraceContext current() {
        return CURRENT.isBound() ? CURRENT.get() : EMPTY;
    }

    public static <T> T where(TraceContext context, Callable<T> operation) throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operation, "operation");
        return java.lang.ScopedValue.where(CURRENT, context).call(operation::call);
    }
}
