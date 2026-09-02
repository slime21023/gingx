# 系統總覽

## 一句話模型

本 runtime 把 Actor 建模成「隔離的 user state 加上 mailbox」。Actor 不會
永久佔用一條平台執行緒；只有在 mailbox 從 idle 變成 runnable 時，系統才
提交一個 Virtual Thread activation。

```text
Application / DSL / HTTP
          │
          ▼
      ActorRef
          │ send() / ask()
          ▼
      ActorCell
      ├── ActorState      lifecycle + scheduling + count
      ├── Mailbox         MPSC chunked queue
      ├── Actor instance
      └── CancellationSource
          │
          ▼
  Virtual Thread activation
          │
          ▼
  Actor.onMessage(message, context)
```

## 主要不變量

系統的正確性建立在以下幾個不變量：

1. 同一個 Actor 同一時間最多一個 activation 執行 `onMessage`。
2. Message 必須先成功取得 mailbox reservation，才會發佈到 queue。
3. Actor activation 取出訊息後才呼叫 user code；失敗中的訊息不會自動重播。
4. 新訊息不會在 ActorSystem 進入 shutdown 後被接受。
5. User state 只應由 Actor 自己讀寫；Producer 透過 message 溝通。

## Layer 1：Java concurrency foundation

`actor-core` 使用 Java 25 的：

- Virtual Thread executor：承載 activation。
- `AtomicLong` / CAS：維護 packed Actor state。
- `VarHandle`：queue slot 的 acquire/release 發布。
- `ScopedValue`：ActorContext、TraceContext 與 ReductionBudget。
- JFR Event：可選的 message lifecycle 記錄。

## Layer 2：Actor runtime

`ActorSystem` 管理整個 runtime 的 lifecycle 與 executor；`ActorCell` 是
內部執行單位；`ActorRef` 是可安全跨執行緒持有的 user-facing handle。

## Layer 3：Groovy preemption

Groovy adapter 在編譯期把 `@Preemptive` method 的 loop 加上 reduction tick。
這是協作式 preemption，不是 JVM 硬體中斷。

## Layer 4：resilience

Supervisor 監控 Actor failure，依策略重啟 child；DeathWatch 提供 termination
通知；CircuitBreaker 保護 Actor 呼叫的下游資源。

## Layer 5：adapters

HTTP、Jackson、Micrometer 與 GINQ 以 extension 形式提供，避免把非必要依賴
帶入 core。
