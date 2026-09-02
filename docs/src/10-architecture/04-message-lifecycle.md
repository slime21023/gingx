# 訊息生命週期

## `send()` 路徑

```text
ActorRef.send(message)
        │
        ├── 檢查 ActorSystem lifecycle
        ├── 取得當前 TraceContext
        ├── 建立 Envelope
        ├── reserve mailbox slot
        ├── queue.offer(envelope)
        └── 必要時提交 activation
```

`Envelope` 包含：

```text
message
reply future (ask 才有)
trace context
```

## activation 路徑

```text
runActivation()
    │ tryStart CAS
    ▼
poll envelope
    │ release mailbox count
    ▼
建立 ActorContext
    │ bind ScopedValue
    ▼
invoke onMessage()
    │
    ├── success → processed counter
    ├── failure → failure listener / supervisor
    └── cancellation → terminate or restart
```

訊息在 `poll()` 後才會減少 mailbox count，接著才呼叫 user code。因此目前
正在執行的訊息不再屬於 mailbox。

## At-most-once delivery

runtime 採用 at-most-once：

```text
poll → remove → invoke
```

如果 `invoke` 失敗，訊息不會自動重新放回 queue。Supervisor restart 時只會
處理尚未被取出的 queued messages。

這避免同一個 side effect 被 runtime 無限重播，但 application 必須自行處理：

- idempotency key。
- 外部 transaction。
- retry policy。
- duplicate request reconciliation。

## `ask()` 路徑

```text
ask(message, timeout)
    │
    ├── 建立 CompletableFuture
    ├── 將 future 放入 Envelope
    ├── future.orTimeout(...)
    └── ActorContext.reply(value) 完成 future
```

如果訊息被拒絕或 drop，future 會以 exception 完成，而不是永久等待。

## 系統訊息

`PoisonPill.INSTANCE` 是特殊 system message。它會在先前已排入的訊息處理後
讓 Actor 終止；終止後的新訊息回傳 `TERMINATED` 或 system lifecycle rejection。
