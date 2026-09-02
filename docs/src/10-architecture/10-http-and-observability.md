# HTTP 與可觀測性

## HTTP request pipeline

```text
JDK HttpServer / HttpsServer
            │
            ▼
     DispatchHandler
            │ route lookup
            ▼
     request executor
            │ timeout boundary
            ▼
        RouteHandler
            │
            └── ActorRef.ask/send
```

HTTP 層將 dispatch executor 與 request executor 分開。request handler 超時
時會取消 task，並回傳 504；server 正在關閉或 executor 被拒絕時回傳 503。

## Request body safety

body limit 同時處理：

1. Content-Length 早期拒絕。
2. Streaming read 的實際 byte count。

超過限制時回傳 413，避免惡意或錯誤 request 無限制佔用 heap。

## JSON 與 core 邊界

`actor-http` 只負責 HTTP exchange、routing、timeout 與 response；Jackson
binding 位於 `actor-http-jackson`。如此 core/HTTP 不必綁定特定 JSON library。

## Metrics

Core 使用 `LongAdder` 提供低爭用 counters：

```text
accepted / full / dropped / processed
failures / restarts / preemptions / cancellations
```

Micrometer adapter 以 `FunctionCounter` 讀取這些值，避免讓 metrics registry
依賴進入 core。

## JFR

JFR message event 只有在 event type enabled 時才建立 event object；未啟用 JFR
時走快速檢查路徑，降低一般執行的額外成本。
