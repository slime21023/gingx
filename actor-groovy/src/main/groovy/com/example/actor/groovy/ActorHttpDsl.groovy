package com.example.actor.groovy

import com.example.actor.http.ActorHttpServer

/** Declarative Groovy facade for the JDK HttpServer adapter. */
public final class ActorHttpDsl {
    private ActorHttpDsl() {
    }

    public static ActorHttpServer build(int port, Closure<?> specification) {
        Objects.requireNonNull(specification, 'specification')
        def routes = new Routes(ActorHttpServer.bind(port))
        def configured = specification.rehydrate(routes, routes, routes)
        configured.resolveStrategy = Closure.DELEGATE_FIRST
        configured.call()
        routes.server
    }

    static final class Routes {
        private final ActorHttpServer server

        private Routes(ActorHttpServer server) {
            this.server = server
        }

        void get(String path, Closure<?> handler) {
            server.get(path) { exchange -> handler.call(exchange) }
        }

        void post(String path, Closure<?> handler) {
            server.post(path) { exchange -> handler.call(exchange) }
        }
    }
}
