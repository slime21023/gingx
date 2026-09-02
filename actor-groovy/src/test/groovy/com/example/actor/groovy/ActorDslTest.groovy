package com.example.actor.groovy

import com.example.actor.Actor
import com.example.actor.ActorOptions
import com.example.actor.ActorSystem
import com.example.actor.http.ActorHttpServer
import org.junit.jupiter.api.Test
import groovy.transform.CompileDynamic

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertEquals

class ActorDslTest {
    @Test
    void leftShiftSendsAMessage() {
        def received = new CompletableFuture<Integer>()
        def system = new ActorSystem()
        def ref = system.spawn({ -> new Actor<Integer>() {
            @Override
            protected void onMessage(Integer message, com.example.actor.ActorContext<Integer> context) {
                received.complete(message)
            }
        }}, ActorOptions.defaults())

        ref << 42
        try {
            assertEquals(42, received.get(5, TimeUnit.SECONDS))
        } finally {
            system.close()
        }
    }

    @Test
    void ginqQueriesActorSnapshot() {
        assertEquals([4, 16], ActorQueries.evenSquares([1, 2, 3, 4]))
    }

    @Test
    @CompileDynamic
    void declarativeHttpDslRoutesToJdkServer() {
        def server = ActorHttpDsl.build(0) {
            get('/hello') { exchange -> ActorHttpServer.text(exchange, 200, 'hello') }
        }
        try {
            server.start()
            def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port()}/hello"))
                    .GET().build()
            def response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals('hello', response.body())
        } finally {
            server.close()
        }
    }
}
