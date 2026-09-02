# Core API

本章是 `actor-core` 的穩定入口。API 文件以「何時使用、有哪些保證、失敗時
會發生什麼」為主；底層演算法請先讀[架構與原理](../10-architecture/README.md)。

## `Actor<M>`

```java
public abstract class Actor<M> {
    protected abstract void onMessage(M message, ActorContext context)
            throws Exception;
}
```

`Actor` 只負責持有 application state 與處理一種 message type。runtime 會在
同一個 activation 中依序呼叫 `onMessage`，因此不需要為每個 handler 額外加
鎖；若 state 會被其他執行緒直接存取，仍需自行同步。

## `ActorSystem`

| API | 用途 |
|---|---|
| `new ActorSystem()` | 使用預設 system 與 actor 設定 |
| `new ActorSystem(ActorSystemOptions)` | 設定預設 mailbox 與 shutdown policy |
| `spawn(factory)` | 建立 actor，回傳對外的 `ActorRef` |
| `spawn(factory, options)` | 以指定 `ActorOptions` 建立 actor |
| `spawnManaged(factory, options)` | 回傳 supervision 使用的 `ManagedActorRef` |
| `metrics()` | 取得累積 runtime counters |
| `actorCount()` | 查看尚未終止的 actor 數量 |
| `shutdown(timeout)` | 以 deadline 啟動 graceful shutdown |
| `close()` | 使用 system 預設 timeout 關閉 |

典型建立方式：

```java
ActorSystemOptions systemOptions = ActorSystemOptions.builder()
        .defaultActorOptions(ActorOptions.builder()
                .mailboxCapacity(2048)
                .maxBatch(128)
                .build())
        .shutdownTimeout(Duration.ofSeconds(15))
        .build();

try (ActorSystem system = new ActorSystem(systemOptions)) {
    ActorRef<Job> ref = system.spawn(JobActor::new,
            ActorOptions.builder().name("jobs").build());
}
```

factory 必須能在 restart 時重新建立乾淨的 actor instance。不要把不可重建的
runtime resource 只放在 actor instance 欄位；將它放在受管理的外部 component，
或在 factory／生命週期鉤子中明確建立與釋放。

## `ActorRef<M>`

`ActorRef` 是 thread-safe 的訊息與生命週期控制面：

```java
SendResult send(M message);
CompletionStage<Object> ask(M message, Duration timeout);
<R> CompletionStage<R> ask(M message, Duration timeout, Class<R> responseType);
String name();
void stop();
void cancel();
boolean isTerminated();
void addTerminationListener(TerminationListener listener);
void removeTerminationListener(TerminationListener listener);
```

`send` 不代表 handler 已執行，只代表訊息在當下被接受、丟棄或拒絕。`ask`
回傳 `CompletionStage`，handler 以 `context.reply(value)` 記錄回覆；timeout、
overflow、actor failure 或 shutdown 都應由呼叫端處理。

Future 在 handler 返回之後才完成，而不是在 `reply()` 當下。掛在其上的
dependent stage 會在 actor 的 activation thread 上執行，但已離開該 actor 的
`ScopedValue` binding；若 stage 的工作不輕量，應自行指定 executor。

Groovy 可使用 `ref << message`；Java 仍建議直接使用 `send`，因為它會回傳
明確的 `SendResult`。

## `ActorOptions`

| 設定 | 預設 | 限制／語意 |
|---|---:|---|
| `name` | `actor` | 監控、JFR 與診斷使用的名稱 |
| `mailboxCapacity` | `1024` | `1..65535`，logical reservation 上限 |
| `overflowStrategy` | `FAIL_FAST` | 滿載時回傳／執行的策略 |
| `maxBatch` | `256` | 一次 activation 最多處理幾則訊息 |
| `reductionBudget` | `4096` | Groovy preemption 的 2 的冪次 budget |

`reductionBudget` 必須是大於等於 2 的 2 的冪次；它只會影響有 reduction tick
的程式路徑。`maxBatch` 是 mailbox 公平性邊界，不能取代 CPU 密集迴圈的
`@Preemptive` instrumentation。

## `ActorContext`

`ActorContext` 僅在 `onMessage` 執行期間有效：

| 方法 | 說明 |
|---|---|
| `self()` | 目前 actor 的 ref |
| `system()` | 所屬 `ActorSystem` |
| `cancellation()` | activation 的取消 token |
| `traceContext()` | 目前訊息攜帶的 trace context |
| `reply(value)` | 記錄目前 `ask` 的回覆，handler 返回後由 runtime 完成；對 `send` 訊息呼叫會丟 `IllegalStateException` |
| `ActorContext.current()` | 由 `ScopedValue` 取得目前 context |

不要把 context 保存到 actor state 或跨執行緒延後使用；需要的值應在 handler
中複製成明確的 application data。

