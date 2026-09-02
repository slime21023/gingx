package com.example.actor.groovy

import groovy.ginq.transform.GQ

/** JVM-only GINQ facade for querying an Actor-owned snapshot. */
public final class ActorQueries {
    private ActorQueries() {
    }

    public static List<Integer> evenSquares(Iterable<Integer> values) {
        GQ {
            from value in values
            where value % 2 == 0
            select value * value
        }.toList()
    }
}
