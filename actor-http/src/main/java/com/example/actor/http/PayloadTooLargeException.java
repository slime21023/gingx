package com.example.actor.http;

import java.io.IOException;

/** Raised when a request body exceeds the configured hard limit. */
public final class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException(long maxBytes) {
        super("request body exceeds " + maxBytes + " bytes");
    }
}
