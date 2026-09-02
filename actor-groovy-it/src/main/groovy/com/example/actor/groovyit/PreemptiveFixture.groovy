package com.example.actor.groovyit

import com.example.actor.groovy.Preemptive

@Preemptive(budget = 8)
class PreemptiveFixture {
    int sum(int limit) {
        int value = 0
        for (int i = 0; i < limit; i++) {
            value += i
        }
        value
    }
}
