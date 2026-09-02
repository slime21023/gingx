# Mailbox 與 Backpressure

## 預設設定

```java
import com.example.actor.ActorOptions;
import com.example.actor.MailboxOverflowStrategy;
import com.example.actor.SendResult;

ActorOptions options = ActorOptions.builder()
        .mailboxCapacity(1024)
        .overflowStrategy(MailboxOverflowStrategy.FAIL_FAST)
        .maxBatch(256)
        .build();
```

Mailbox capacity 是 logical reservation 上限；queue 本身是一個容量向上取到
2 的冪次的定容 ring，因此物理容量恆不小於它。

## 策略選擇

| 策略 | `send` 結果 | 適用情境 |
|---|---|---|
| `FAIL_FAST` | `FULL` | 不能接受排隊超載，讓上游 retry 或降級 |
| `DROP_LATEST` | `DROPPED` | 最新資料沒有價值 |
| `DROP_OLDEST` | `ACCEPTED_AFTER_DROP` | 只需要最新狀態 |

所有結果都應被視為正常控制流程，而不是例外：

```java
switch (ref.send(message)) {
    case ACCEPTED, ACCEPTED_AFTER_DROP -> { }
    case FULL -> scheduleRetry(message);
    case DROPPED -> recordDrop(message);
    case TERMINATED, SYSTEM_SHUTTING_DOWN, SYSTEM_CLOSED -> failFast(message);
}
```

## `ask` 與 overflow

如果 `ask` message 被 drop 或拒絕，對應 future 會以 exception 完成，不應讓
呼叫端永久等待。對 `ask` 呼叫設定 timeout 是必要的，即使 mailbox 看起來很空。

## Backpressure 不等於無限重試

錯誤模式：

```text
FULL → 立即 retry → FULL → 立即 retry → CPU spin
```

應使用 delay、token bucket、上游限流或 circuit breaker。retry 應有上限，並
留下 dropped/full metrics。

## DropOldest 的成本

`DROP_OLDEST` 必須將 queue 中最早的 envelope 取出，再釋放 reservation，因此
enqueue 與 consumer 需要同步協調。這個策略提供語意便利，但不是最純粹的
lock-free fast path。
