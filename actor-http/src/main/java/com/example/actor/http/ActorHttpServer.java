package com.example.actor.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production-limited actor-friendly HTTP adapter backed by JDK HttpServer. */
public final class ActorHttpServer implements AutoCloseable {
    @FunctionalInterface
    public interface RouteHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    public static final class Options {
        private final InetSocketAddress bindAddress;
        private final long maxRequestBodyBytes;
        private final Duration requestTimeout;
        private final Duration shutdownTimeout;
        private final SSLContext sslContext;

        private Options(Builder builder) {
            this.bindAddress = builder.bindAddress;
            this.maxRequestBodyBytes = builder.maxRequestBodyBytes;
            this.requestTimeout = builder.requestTimeout;
            this.shutdownTimeout = builder.shutdownTimeout;
            this.sslContext = builder.sslContext;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Options defaults() {
            return builder().build();
        }

        public InetSocketAddress bindAddress() { return bindAddress; }
        public long maxRequestBodyBytes() { return maxRequestBodyBytes; }
        public Duration requestTimeout() { return requestTimeout; }
        public Duration shutdownTimeout() { return shutdownTimeout; }
        public SSLContext sslContext() { return sslContext; }

        public static final class Builder {
            private InetSocketAddress bindAddress = new InetSocketAddress("127.0.0.1", 0);
            private long maxRequestBodyBytes = 1_048_576L;
            private Duration requestTimeout = Duration.ofSeconds(30);
            private Duration shutdownTimeout = Duration.ofSeconds(30);
            private SSLContext sslContext;

            public Builder bindAddress(InetSocketAddress address) {
                this.bindAddress = Objects.requireNonNull(address, "bindAddress");
                return this;
            }

            public Builder port(int port) {
                if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range");
                return bindAddress(new InetSocketAddress(bindAddress.getHostString(), port));
            }

            public Builder maxRequestBodyBytes(long bytes) {
                if (bytes < 1 || bytes > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("maxRequestBodyBytes must be between 1 and Integer.MAX_VALUE");
                }
                this.maxRequestBodyBytes = bytes;
                return this;
            }

            public Builder requestTimeout(Duration timeout) {
                this.requestTimeout = positive(timeout, "requestTimeout");
                return this;
            }

            public Builder shutdownTimeout(Duration timeout) {
                this.shutdownTimeout = positive(timeout, "shutdownTimeout");
                return this;
            }

            public Builder sslContext(SSLContext context) {
                this.sslContext = context;
                return this;
            }

            public Options build() {
                return new Options(this);
            }

            private static Duration positive(Duration value, String name) {
                Objects.requireNonNull(value, name);
                if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
                return value;
            }
        }
    }

    private final HttpServer server;
    private final ExecutorService dispatchExecutor;
    private final ExecutorService requestExecutor;
    private final Options options;
    private final List<Route> routes = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private ActorHttpServer(HttpServer server, Options options) {
        this.server = server;
        this.options = options;
        this.dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/", new DispatchHandler());
        server.setExecutor(dispatchExecutor);
    }

    public static ActorHttpServer bind(int port) throws IOException {
        return bind(Options.builder().port(port).build());
    }

    public static ActorHttpServer bind(Options options) throws IOException {
        Objects.requireNonNull(options, "options");
        HttpServer server;
        if (options.sslContext() == null) {
            server = HttpServer.create(options.bindAddress(), 0);
        } else {
            HttpsServer httpsServer = HttpsServer.create(options.bindAddress(), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(options.sslContext()));
            server = httpsServer;
        }
        return new ActorHttpServer(server, options);
    }

    public static ActorHttpServer bind(int port, SSLContext sslContext) throws IOException {
        return bind(Options.builder().port(port).sslContext(sslContext).build());
    }

    public synchronized ActorHttpServer route(String method, String path, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        if (!path.startsWith("/")) throw new IllegalArgumentException("path must start with '/'");
        routes.add(new Route(method.toUpperCase(java.util.Locale.ROOT), path, handler));
        return this;
    }

    public ActorHttpServer get(String path, RouteHandler handler) {
        return route("GET", path, handler);
    }

    public ActorHttpServer post(String path, RouteHandler handler) {
        return route("POST", path, handler);
    }

    public void start() {
        if (started.compareAndSet(false, true)) server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Options options() {
        return options;
    }

    /** Reads a request body while enforcing the configured hard byte limit. */
    public static byte[] readBody(HttpExchange exchange, long maxBytes) throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be between 1 and Integer.MAX_VALUE");
        }
        String contentLength = exchange.getRequestHeaders().getFirst("Content-length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > maxBytes) throw new PayloadTooLargeException(maxBytes);
            } catch (NumberFormatException ignored) {
                // The streaming limit below remains authoritative.
            }
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new PayloadTooLargeException(maxBytes);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public static byte[] readBody(HttpExchange exchange, Options options) throws IOException {
        return readBody(exchange, Objects.requireNonNull(options, "options").maxRequestBodyBytes());
    }

    public static void text(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        server.stop((int) Math.min(Integer.MAX_VALUE, Math.max(0L, timeout.toSeconds())));
        dispatchExecutor.shutdown();
        requestExecutor.shutdown();
        await(requestExecutor, timeout);
        if (!requestExecutor.isTerminated()) requestExecutor.shutdownNow();
        if (!dispatchExecutor.isTerminated()) dispatchExecutor.shutdownNow();
    }

    @Override
    public void close() {
        close(options.shutdownTimeout());
    }

    private static void await(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class DispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Route route = find(exchange.getRequestMethod(), exchange.getRequestURI().getPath());
            if (route == null) {
                text(exchange, 404, "not found");
                exchange.close();
                return;
            }
            Future<?> task = null;
            try {
                task = requestExecutor.submit(() -> {
                    try {
                        route.handler.handle(exchange);
                    } catch (Exception failure) {
                        throw new HandlerFailure(failure);
                    }
                });
                task.get(options.requestTimeout().toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException rejected) {
                respondIfUncommitted(exchange, 503, "server shutting down");
            } catch (TimeoutException timeout) {
                if (task != null) task.cancel(true);
                respondIfUncommitted(exchange, 504, "request timeout");
            } catch (InterruptedException interrupted) {
                if (task != null) task.cancel(true);
                Thread.currentThread().interrupt();
                respondIfUncommitted(exchange, 503, "request interrupted");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof HandlerFailure handlerFailure
                        && handlerFailure.getCause() instanceof PayloadTooLargeException) {
                    respondIfUncommitted(exchange, 413, "request body too large");
                } else {
                    respondIfUncommitted(exchange, 500, "internal server error");
                }
            } finally {
                exchange.close();
            }
        }
    }

    private void respondIfUncommitted(HttpExchange exchange, int status, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        if (exchange.getResponseCode() < 0) text(exchange, status, body);
        else headers.set("X-Actor-Response", "committed");
    }

    private synchronized Route find(String method, String path) {
        for (Route route : routes) {
            if (route.method.equalsIgnoreCase(method) && route.path.equals(path)) return route;
        }
        return null;
    }

    private record Route(String method, String path, RouteHandler handler) {
    }

    private static final class HandlerFailure extends RuntimeException {
        private HandlerFailure(Throwable cause) {
            super(cause);
        }
    }
}

