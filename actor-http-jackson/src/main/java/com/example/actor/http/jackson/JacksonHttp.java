package com.example.actor.http.jackson;

import com.example.actor.http.ActorHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Objects;

/** Optional Jackson integration for bounded HTTP request/response JSON. */
public final class JacksonHttp {
    private final ObjectMapper mapper;

    public JacksonHttp() {
        this(new ObjectMapper());
    }

    public JacksonHttp(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public <T> T read(HttpExchange exchange, Class<T> type, ActorHttpServer.Options options) throws IOException {
        Objects.requireNonNull(type, "type");
        return mapper.readValue(ActorHttpServer.readBody(exchange, options), type);
    }

    public byte[] write(Object value) throws IOException {
        return mapper.writeValueAsBytes(value);
    }

    public void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = write(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

