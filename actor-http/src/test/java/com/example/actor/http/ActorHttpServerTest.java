package com.example.actor.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorHttpServerTest {
    @Test
    void routesRequestAndReturnsJson() throws Exception {
        try (ActorHttpServer server = ActorHttpServer.bind(0)) {
            server.get("/health", exchange -> ActorHttpServer.json(exchange, 200, "{\"ok\":true}"));
            server.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("{\"ok\":true}", response.body());
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        }
    }

    @Test
    void rejectsOversizedRequestBody() throws Exception {
        ActorHttpServer.Options options = ActorHttpServer.Options.builder()
                .port(0).maxRequestBodyBytes(4).build();
        try (ActorHttpServer server = ActorHttpServer.bind(options)) {
            server.post("/limited", exchange -> {
                ActorHttpServer.readBody(exchange, server.options());
                ActorHttpServer.text(exchange, 200, "ok");
            });
            server.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/limited"))
                    .POST(HttpRequest.BodyPublishers.ofString("12345")).build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode());
        }
    }

    @Test
    void timesOutLongRunningHandler() throws Exception {
        ActorHttpServer.Options options = ActorHttpServer.Options.builder()
                .port(0).requestTimeout(Duration.ofMillis(50)).build();
        try (ActorHttpServer server = ActorHttpServer.bind(options)) {
            server.get("/slow", exchange -> Thread.sleep(Duration.ofSeconds(2)));
            server.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/slow"))
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode());
        }
    }
}
