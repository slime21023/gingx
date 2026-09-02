# Maven 模組與依賴邊界

Root POM 是 Maven reactor，模組依賴方向應維持由底層向上：

```text
actor-core
   ├── actor-supervision
   ├── actor-http
   │      └── actor-http-jackson
   ├── actor-observability-micrometer
   ├── actor-groovy
   │      └── actor-groovy-it   (integration-test only)
   ├── actor-demo
   ├── actor-benchmarks         (benchmark only)
   ├── actor-stress              (stress-test only)
   ├── actor-testkit             (test support)
   └── actor-tck                 (contract-test only)
```

## Production modules

### `actor-core`

必要的 Java runtime：queue、Actor、狀態機、排程、cancellation、shutdown、
ScopedValue、metrics 與 JFR。

### `actor-supervision`

依賴 core，提供 restart strategy、supervisor tree、DeathWatch 與 CircuitBreaker。

### `actor-http`

依賴 core，包裝 JDK `HttpServer` / `HttpsServer`。它不引入 Jackson，也不
負責 JSON binding。

### `actor-groovy`

依賴 core 與 HTTP，提供 Groovy DSL、GINQ facade 與 `@Preemptive` AST transformation。
Groovy 只在需要 DSL 或 AST instrumentation 時引入。

### Optional extensions

- `actor-http-jackson`：Jackson request/response binding。
- `actor-observability-micrometer`：將 core counters 綁定到 Micrometer registry。

## Test-only modules

以下模組不是 runtime dependency：

- `actor-testkit`：`TestProbe`、`TestScheduler` 虛擬時間與 quiescence 等待。
  它只依賴 core，應以 `test` scope 引入。

- `actor-groovy-it`：Groovy compile/integration fixture。
- `actor-stress`：壓力測試與 opt-in 百萬 Actor memory gate。
- `actor-tck`：runtime contract 與 fault-injection 測試。
- `actor-benchmarks`：JMH executable jar。

應用程式通常只需要：

```text
actor-core
actor-supervision       optional
actor-http              optional
actor-http-jackson      optional
actor-observability...  optional
actor-groovy            optional
```

## 依賴設計原則

1. Core 不依賴 Groovy、Jackson 或 Micrometer。
2. Extension 只能向下依賴，不應反向修改 core contract。
3. Test-only module 不應被 application POM 使用。
4. Benchmark 與 production code 分離，避免 JMH 進入 runtime classpath。
