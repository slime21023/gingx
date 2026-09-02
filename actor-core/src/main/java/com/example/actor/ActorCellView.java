package com.example.actor;

/**
 * The internal surface an {@link ActorContext} needs from the cell it belongs
 * to. Keeping it package private stops the message-buffer controls leaking
 * into the public reference type.
 */
interface ActorCellView<M> extends ActorRef<M>, Timers<M> {
    <C> ActorRef<C> spawnChild(java.util.function.Supplier<? extends Actor<C>> factory, ActorOptions options);

    int childCount();

    void stashCurrent();

    void requestUnstashAll();

    int stashSize();
}
