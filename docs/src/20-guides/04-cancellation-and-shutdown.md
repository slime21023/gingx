# Cancellation 與 Graceful Shutdown

## Actor cancellation

```java
ActorRef<Job> worker = system.spawn(() -> new Actor<>() {
    @Override
    protected void onMessage(Job job, ActorContext context) {
        while (job.hasMoreWork()) {
            context.cancellation().throwIfCancelled();
            job.processNext();
        }
    }
});
```

`cancel()` 會設定 cancellation state，並嘗試 interrupt activation thread。這是
cooperative contract：user code 必須檢查 token，blocking API 也必須正確處理
`InterruptedException`。

## `stop` 與 `cancel`

- `cancel()` 是明確的 user/application cancellation，會增加 cancellation metric。
- `stop()` 是 lifecycle termination request，常由 system 或 supervisor 使用。

兩者都不應依賴 `Thread.stop()`。

## 系統 shutdown

```java
ShutdownReport report = system.shutdown(Duration.ofSeconds(30));
if (!report.terminated()) {
    log.warn("Actor system did not terminate in time: {}", report);
}
```

shutdown 狀態是：

```text
OPEN → SHUTTING_DOWN → CLOSED
```

進入 `SHUTTING_DOWN` 後新的 send 會被拒絕；active handler 會收到 cancellation
與 interrupt；runtime 等到 deadline 後才回傳 report。

## 部署順序

推薦順序：

1. 停止接受新的 HTTP request。
2. 停止上游 Producer。
3. 呼叫 `ActorSystem.shutdown(timeout)`。
4. 檢查 `ShutdownReport`。
5. 若仍有外部資源，最後關閉它們。

不要只依賴 JVM process exit；在 container 或 rolling deployment 中必須明確
執行 shutdown。
