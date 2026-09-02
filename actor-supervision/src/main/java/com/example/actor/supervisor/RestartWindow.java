package com.example.actor.supervisor;

import java.time.Duration;
import java.util.Objects;

/** Fixed-size circular timestamp buffer for deterministic crash-loop limiting. */
final class RestartWindow {
    private final long[] timestamps;
    private final long windowNanos;
    private int cursor;
    private int size;

    RestartWindow(int maximumRestarts, Duration window) {
        if (maximumRestarts < 1) throw new IllegalArgumentException("maximumRestarts must be positive");
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be positive");
        this.timestamps = new long[maximumRestarts];
        this.windowNanos = window.toNanos();
    }

    synchronized boolean allow(long now) {
        if (size < timestamps.length) {
            timestamps[(cursor + size) % timestamps.length] = now;
            size++;
            return true;
        }
        long oldest = timestamps[cursor];
        if (now - oldest >= windowNanos) {
            timestamps[cursor] = now;
            cursor = (cursor + 1) % timestamps.length;
            return true;
        }
        return false;
    }

    synchronized int size() {
        return size;
    }
}
