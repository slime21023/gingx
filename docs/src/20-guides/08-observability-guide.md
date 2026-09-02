# Metrics、JFR 與 Trace Context

## Core metrics

```java
ActorMetrics.Snapshot snapshot = system.metrics().snapshot();
```

可觀察 counters 包含：

```text
accepted, full, dropped, processed,
failures, restarts, preemptions, cancellations
```

`full` 與 `dropped` 特別適合用來判斷 upstream 是否需要 backpressure；
`failures` 與 `restarts` 可用來偵測 supervisor crash loop。

## Micrometer extension

```java
new ActorMetricsBinder(system.metrics()).bindTo(meterRegistry);
```

Micrometer adapter 位於獨立 module，application 可自行選擇 Prometheus、OTel
或其他 registry。

## JFR

JFR message event 可用於分析：

- 哪些 Actor 處理時間較長。
- 哪些 message type failure 比例較高。
- request 到 Actor handler 的 trace 關聯。

未啟用 JFR 時，runtime 不會為每一筆 message 建立 JFR event object。

## Trace context

```java
TraceContext.where(new TraceContext("request-123"), () -> {
    actor.send(message);
    return null;
});
```

Actor 內送出的 message 會沿用當前 ActorContext 的 trace context；跨 HTTP、
Actor 與下游 service 時，application 仍應把 trace id 映射到自己的 logging/
tracing system。
