# Actor 執行模型

## Actor 不是長駐執行緒

`ActorRef` 只是一個 handle；Actor 的執行由 `ActorCell` 控制。當 mailbox
沒有訊息時，Actor 沒有活躍的執行 task，也不會獨佔 carrier thread。

```text
idle Actor
    │ first send()
    ▼
runnable Actor
    │ executor.submit(runActivation)
    ▼
running Actor
    │ batch 完成
    ├── mailbox empty  → idle
    └── more messages  → runnable again
```

## Single-threaded invariant

Producer 不直接呼叫 `onMessage`。Producer 只做兩件事：

1. 將訊息加入 mailbox。
2. 在需要時提交 activation。

activation 開始時必須透過 `ActorState.tryStart()` CAS。只有一個 task 能將
狀態從 `RUNNABLE` 改成 `RUNNING`，因此即使多個 Producer 同時看到訊息，
也不會產生兩個同時執行的 Actor handler。

## Batch fairness

每次 activation 最多處理 `ActorOptions.maxBatch()` 筆訊息，預設為 256。
這是訊息層級的公平性控制：

```text
Actor A: [m1 m2 ... m256] → 重新排程
Actor B: [n1 n2 ...]
```

它不會中斷一個正在執行的 `onMessage`。如果單一訊息本身包含長時間 CPU
迴圈，需要使用 Groovy `@Preemptive` 或自行呼叫 cancellation/yield 機制。

## Actor factory 與 restart

Actor factory、mailbox 與 cancellation source 都採 lazy initialization：

- 建立大量 idle Actor 時，不立即建立 user Actor instance。
- 第一次 activation 時才呼叫 factory。
- Restart 時重新呼叫 factory，建立新的 user state。
- 尚未處理的 mailbox message 保留給新的 instance。

這讓 user 可以用 immutable factory 建立可重建的 Actor；不可重建的外部資源
應放在 supervisor 或 application lifecycle，而不是依賴 Actor instance 永久存在。

## User state 規則

Actor 的 `onMessage` 具 thread confinement，但 message Producer 可能位於任何
執行緒。應遵循：

- Message 使用 immutable value object。
- 不要在送出後修改 message 內容。
- 不要從其他執行緒直接讀寫 Actor mutable fields。
- 需要回覆時使用 `ActorContext.reply()` 或明確傳送另一個 message。
