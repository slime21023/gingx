package com.example.actor.http.jackson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonHttpTest {
    @Test
    void serializesProductionPayloadsWithJackson() throws Exception {
        String json = new String(new JacksonHttp().write(new Payload("ok")));
        assertTrue(json.contains("\"message\":\"ok\""));
    }

    private record Payload(String message) {
    }
}

