# HTTP、JSON 與 TLS

## 基本 HTTP server

```java
ActorHttpServer.Options options = ActorHttpServer.Options.builder()
        .port(8080)
        .maxRequestBodyBytes(1_048_576)
        .requestTimeout(Duration.ofSeconds(5))
        .build();

try (ActorHttpServer server = ActorHttpServer.bind(options)
        .get("/health", exchange -> ActorHttpServer.json(
                exchange, 200, "{\"ok\":true}"))) {
    server.start();
    Thread.currentThread().join();
}
```

`ActorHttpServer` 使用 JDK HttpServer；handler 可呼叫 ActorRef，但 HTTP 層本身
不會替 application 決定 actor topology。

## Request body limit

```java
byte[] body = ActorHttpServer.readBody(exchange, options);
```

Content-Length 過大或實際 stream 超過限制時會產生 413。不要使用無界的
`exchange.getRequestBody().readAllBytes()` 處理外部 request。

## Actor-backed request

```java
server.post("/echo", exchange -> {
    String input = new String(
            ActorHttpServer.readBody(exchange, options),
            StandardCharsets.UTF_8);
    String result = actor.ask(input, Duration.ofSeconds(2), String.class)
            .toCompletableFuture()
            .join();
    ActorHttpServer.json(exchange, 200, result);
});
```

需要同時設定 HTTP handler timeout 與 Actor ask timeout，避免其中一層無限等待。

## JSON extension

`actor-http-jackson` 將 Jackson ObjectMapper 包裝成 bounded read/write helper：

```java
JacksonHttp json = new JacksonHttp(objectMapper);
UserRequest request = json.read(exchange, UserRequest.class, options);
json.json(exchange, 200, response);
```

Jackson 是 optional module，core 與純文字 HTTP 不會強制引入它。

## TLS

```java
ActorHttpServer.bind(
        ActorHttpServer.Options.builder()
                .port(8443)
                .sslContext(sslContext)
                .build());
```

`SSLContext`、certificate rotation、cipher policy 與 trust store 由 deployment
負責；runtime 只提供 server wiring。
