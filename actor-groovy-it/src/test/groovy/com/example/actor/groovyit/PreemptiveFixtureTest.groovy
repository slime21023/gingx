package com.example.actor.groovyit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class PreemptiveFixtureTest {
    @Test
    void transformLeavesProgramSemanticsUnchanged() {
        assertEquals(45, new PreemptiveFixture().sum(10))
    }
}
