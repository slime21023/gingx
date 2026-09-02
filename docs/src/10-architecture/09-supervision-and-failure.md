# Supervision、Failure 與生命週期

## Failure 邊界

Actor 執行 handler 時，runtime 會區分：

```text
queued message       尚未取出，仍在 mailbox
in-flight message    已取出，正在 user code 中
```

Handler 失敗時，in-flight message 不會放回 mailbox；queued messages 才能在
restart 後繼續交給新的 Actor instance。

## Supervisor tree

```text
root Supervisor
├── worker-a
├── worker-b
└── payments Supervisor
    ├── debit
    └── credit
```

每個 Supervisor 管理 direct children 與 nested subtrees。Child 以 factory
建立，使 restart 可以重建乾淨的 user state。

## Restart strategies

| Strategy | 行為 |
|---|---|
| `ONE_FOR_ONE` | 只重啟失敗 child |
| `ONE_FOR_ALL` | 重啟同一 Supervisor 下所有 children |
| `REST_FOR_ONE` | 依註冊順序，重啟失敗 child 及其後續 children |

## Crash-loop protection

Supervisor 使用 circular timestamp buffer 記錄 restart：

```text
restart 1 ─┐
restart 2  │ sliding window
restart 3  │
restart 4 ─┘
```

若視窗內重啟次數超出上限，Supervisor 停止自己的 subtree，避免無限 crash /
restart 消耗 CPU。

## DeathWatch

DeathWatch 以 termination listener 監控 Actor：

```text
observer.watch(target)
        ↓
target terminates
        ↓
observer.send(Terminated(target))
```

這是 lifecycle notification，不應與一般業務 message 混淆。

## CircuitBreaker

```text
CLOSED ── threshold exceeded ──> OPEN
  ▲                              │
  │ successful probe             │ reset timeout
  └──────── HALF_OPEN <──────────┘
```

`HALF_OPEN` 只允許一個 probe，避免下游恢復期間突然湧入大量請求。
