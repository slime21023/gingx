package com.example.actor;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public interface ActorRef<M> {
    SendResult send(M message);

    /**
     * Sends a request that carries its own reply address.
     *
     * <p>This is the type-safe form of ask: the factory receives the address to
     * answer, so the response type is checked at compile time and the handler
     * answers with an ordinary {@code replyTo.send(...)} instead of an implicit
     * per-message protocol.</p>
     *
     * <pre>{@code
     * record GetBalance(String account, ActorRef<Balance> replyTo) {}
     *
     * CompletionStage<Balance> balance = accounts.ask(
     *         Duration.ofSeconds(2), replyTo -> new GetBalance("acc-1", replyTo));
     * }</pre>
     */
    <R> CompletionStage<R> ask(Duration timeout, Function<ActorRef<R>, M> messageFactory);

    /**
     * The same request, with the reply type stated explicitly.
     *
     * <p>Java infers {@code R} from what the result is assigned to, which does
     * not work when the call is chained. Naming the type restores inference:
     * {@code ref.ask(Balance.class, timeout, GetBalance::new).thenApply(...)}.
     * The type is a witness for the compiler, not a runtime cast.</p>
     */
    <R> CompletionStage<R> ask(Class<R> responseType, Duration timeout,
                               Function<ActorRef<R>, M> messageFactory);

    /**
     * @deprecated Use {@link #ask(Duration, Function)}. The reply type of this
     *             form is unchecked and the handler cannot tell from the
     *             message whether an answer is expected.
     */
    @Deprecated(forRemoval = true)
    CompletionStage<Object> ask(M message, Duration timeout);

    /**
     * @deprecated Use {@link #ask(Duration, Function)}, which checks the reply
     *             type at compile time instead of casting at runtime.
     */
    @Deprecated(forRemoval = true)
    <R> CompletionStage<R> ask(M message, Duration timeout, Class<R> responseType);

    String name();

    void stop();

    void cancel();

    boolean isTerminated();

    void addTerminationListener(TerminationListener listener);

    void removeTerminationListener(TerminationListener listener);

    /** Groovy maps {@code ref << message} to this method. */
    default ActorRef<M> leftShift(M message) {
        send(message);
        return this;
    }
}
