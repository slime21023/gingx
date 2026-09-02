package com.example.actor.demo;

import com.example.actor.Actor;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.http.ActorHttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class DemoApplication {
    private DemoApplication() {
    }

    public static void main(String[] args) throws Exception {
        int requestedPort = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        ActorSystem system = new ActorSystem();
        ActorRef<String> echo = system.spawn(() -> new Actor<>() {
            @Override
            protected void onMessage(String message, com.example.actor.ActorContext<String> context) {
                context.reply("{\"message\":\"" + escape(message) + "\"}");
            }
        }, ActorOptions.builder().name("echo").build());

        ActorHttpServer.Options httpOptions = ActorHttpServer.Options.builder()
                .port(requestedPort).maxRequestBodyBytes(1_048_576).build();
        ActorHttpServer server = ActorHttpServer.bind(httpOptions)
                .get("/health", exchange -> ActorHttpServer.json(exchange, 200, "{\"ok\":true}"))
                .post("/echo", exchange -> {
                    String body = new String(ActorHttpServer.readBody(exchange, httpOptions), StandardCharsets.UTF_8);
                    String response = (String) echo.ask(body, Duration.ofSeconds(2))
                            .toCompletableFuture().get();
                    ActorHttpServer.json(exchange, 200, response);
                });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            system.close();
        }));
        server.start();
        System.out.println("Actor demo listening on http://127.0.0.1:" + server.port());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
