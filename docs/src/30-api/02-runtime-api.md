# Runtime API

## `SendResult`

所有送信結果都是公開協定的一部分：

| 結果 | 意義 | 建議處置 |
|---|---|---|
| `ACCEPTED` | message 已取得 mailbox reservation | 正常繼續 |
| `ACCEPTED_AFTER_DROP` | 依 `DROP_OLDEST` 先移除舊 message 後接受 | 記錄資料遺失並繼續 |
| `FULL` | `FAIL_FAST` mailbox 沒有容量 | 延遲 retry、降級或拒絕上游 |
| `DROPPED` | `DROP_LATEST` 丟棄新 message | 記錄 drop；只適用可遺失資料 |
| `TERMINATED` | target actor 已終止 | 停止送信或重新取得 actor |
| `SYSTEM_SHUTTING_DOWN` | system 已進入關閉流程 | 停止產生新工作 |
| `SYSTEM_CLOSED` | system 已關閉 | 修正生命週期或回報服務不可用 |

不要只判斷 `result != null`；`send` 不以 exception 表示正常 backpressure。

## Mailbox 設定

`MailboxOverflowStrategy` 目前包含：

- `FAIL_FAST`：保留現有訊息，回傳 `FULL`。
- `DROP_LATEST`：拒絕最新訊息，回傳 `DROPPED`。
- `DROP_OLDEST`：移除最早訊息，再回傳 `ACCEPTED_AFTER_DROP`。

Mailbox 的容量是每個 actor 的 logical count，與 MPSC queue chunk size 不同。
應依 message 的遺失容忍度選擇策略，而不是用 drop 策略掩蓋未處理的 overload。

## Cancellation

```java
CancellationSource source = new CancellationSource();
CancellationToken token = source.token();

if (token.isCancelled()) {
    return;
}
token.throwIfCancelled();
source.cancel();
```

`CancellationSource.cancel()` 只在第一次成功改變狀態時回傳 `true`。在 actor
handler 中使用 `context.cancellation()`；在長迴圈、批次 I/O 邊界與重試迴圈
主動檢查。取消是協作式的，不保證能中止不響應的 native call 或外部服務。

## Shutdown

`ActorSystem.shutdown(Duration)` 回傳：

```java
public record ShutdownReport(
        Duration timeout,
        Duration elapsed,
        int remainingActors,
        boolean terminated) { }
```

`terminated == false` 表示 deadline 到期時仍有 actor 未完成；這不是成功關閉。
服務應記錄 `remainingActors`，並依部署策略決定是否終止 process。`close()` 使用
`ActorSystemOptions.shutdownTimeout()`。

推薦的關閉順序是：停止 ingress → 停止 retry／排程器 → 呼叫 system shutdown
→ 檢查 report → 關閉外部 resource。

## System messages and listeners

- `PoisonPill.INSTANCE` 會在先前 mailbox message 處理完後終止 actor。
- `TerminationListener` 在 actor 終止時收到通知。
- `FailureListener` 供 supervision 接收 handler failure。
- `ActorFailure` 是包含 actor 與 cause 的 failure value。
- `ActorCrashedException` 表示 actor activation 因 user failure 崩潰。

Poison pill 不是立即中斷；要取消長時間 activation，使用 `cancel()`／cancellation
token，並讓 user code 在安全點退出。

## `TraceContext`

```java
TraceContext trace = new TraceContext("req-123");
String value = TraceContext.where(trace, () -> TraceContext.current().traceId());
```

runtime 以 Java `ScopedValue` 傳遞 immutable context。context 應是小型、不可變
的識別資料；request body、security principal 或可變 map 不應直接塞進 context。

