package com.example.actor.groovy

import com.example.actor.ActorRef

/** Small Groovy-facing facade; ActorRef#leftShift also enables `ref << message`. */
public final class ActorDsl {
    public static <M> ActorRef<M> send(ActorRef<M> ref, M message) {
        ref.send(message)
        ref
    }
}
