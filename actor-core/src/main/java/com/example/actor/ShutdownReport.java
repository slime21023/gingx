package com.example.actor;

import java.time.Duration;

/** Result of a deadline-bounded actor-system shutdown. */
public record ShutdownReport(Duration timeout, Duration elapsed, int remainingActors, boolean terminated) {
}
