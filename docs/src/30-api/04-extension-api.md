# Extension API

## HTTP：`ActorHttpServer`

`actor-http` 是 JDK `HttpServer` 的受限 adapter，提供精確路由、Virtual Thread
request dispatch、body byte limit、request timeout、graceful close 與 optional TLS。

```java
ActorHttpServer.Options options = ActorHttpServer.Options.builder()
        .port(8080)
        .maxRequestBodyBytes(1_048_576)
        .requestTimeout(Duration.ofSeconds(5))
        .shutdownTimeout(Duration.ofSeconds(10))
        .build();

try (ActorHttpServer server = ActorHttpServer.bind(options)
        .get("/health", exchange -> ActorHttpServer.text(exchange, 200, "ok"))) {
    server.start();
}
```

重要方法：

| API | 說明 |
|---|---|
| `bind(int)`／`bind(options)` | 建立尚未啟動的 server |
| `get(path, handler)`／`post(path, handler)` | 註冊 HTTP route |
| `route(method, path, handler)` | 註冊任意 method |
| `start()` | 啟動 server；可重複呼叫 |
| `port()` | 取得實際 bind port，適合測試用 port `0` |
| `readBody(exchange, maxBytes)` | 以 hard limit 讀取 body |
| `text(...)`／`json(...)` | 寫出 UTF-8 response |
| `close(timeout)`／`close()` | 停止 ingress 並等待 request executor |

`RouteHandler` 例外會被轉換成 500；body 超限會轉換成 413；request deadline
會取消 handler 並回 504（若 response 尚未 commit）。adapter 不替 application
建立 actor topology，也不替 JSON parser 做 schema validation。

## Jackson：`JacksonHttp`

`actor-http-jackson` 是 optional module：

```java
JacksonHttp json = new JacksonHttp(objectMapper);
UserRequest request = json.read(exchange, UserRequest.class, options);
json.json(exchange, 200, new UserResponse("ok"));
```

實際簽名是 `read(HttpExchange, Class<T>, ActorHttpServer.Options)`。它使用
`ActorHttpServer.readBody` 的 bounded input，再交給 `ObjectMapper`；請勿繞過
此 helper 直接對外部 request 呼叫無界 `readAllBytes()`。

## Micrometer：`ActorMetricsBinder`

```java
new ActorMetricsBinder(system.metrics()).bindTo(registry);
```

binder 將 core `ActorMetrics` snapshot 對應到 registry。它不會把每個 actor name
當成 tag；若需要 per-actor 維度，請先評估高 cardinality 與 metrics backend 成本。

## Groovy：DSL、GINQ 與 preemption

- `ActorDsl.send(ref, message)` 與 `ActorRef.leftShift` 提供 `ref << message`。
- `ActorHttpDsl.build(port) { ... }` 提供 `get`／`post` closure route。
- `ActorQueries.evenSquares(values)` 示範對 actor-owned snapshot 使用 GINQ。
- `@Preemptive(budget = 4096)` 在編譯期織入 loop reduction tick。

`@Preemptive` 是 source-retention AST annotation，只在 Groovy 編譯時生效；Java
class、反射載入的 bytecode 與 native call 不會自動獲得 preemption。

