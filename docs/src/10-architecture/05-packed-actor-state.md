# Packed CAS 狀態機

`ActorState` 將 scheduler 所需的資訊壓縮在一個 `AtomicLong`。這不是一般的
資料 transfer object，而是 Actor concurrency protocol 的核心。

## Bit layout

| Bits | 欄位 | 用途 |
|---|---|---|
| 0–2 | lifecycle | `NEW` 到 `TERMINATED` |
| 3 | scheduled | 是否已有待執行 activation |
| 4 | cancelled | activation 是否被取消 |
| 5 | suspended | 是否暫停 |
| 8–23 | mailbox count | 已 reservation、尚未完成的 message 數量 |
| 24–55 | generation | restart 世代 |

中間保留 bits 讓未來可擴充，而不必立刻改變 state representation。

## 主要轉移

```text
NEW --initialize()----------------------------> IDLE
IDLE --reserveMessage()-----------------------> RUNNABLE + scheduled
RUNNABLE --tryStart()------------------------> RUNNING
RUNNING --completeRun(), count=0-------------> IDLE
RUNNING --completeRun(), count>0-------------> RUNNABLE
any active --requestStop()-------------------> STOPPING
STOPPING --terminate()-----------------------> TERMINATED
failure --fail()-----------------------------> IDLE + generation + 1
```

## 為什麼 scheduled 要獨立存在

如果只有 lifecycle，兩個 Producer 可能同時看到 `IDLE`：

```text
Producer A: sees IDLE
Producer B: sees IDLE
Producer A: submit activation
Producer B: submit activation
```

`reserveMessage()` 以一次 CAS 同時設定 `RUNNABLE` 和 `scheduled`，只有一個
Producer 取得 schedule transition。

## mailbox count 的角色

Queue 本身是 chunked storage；capacity enforcement 在 ActorState 完成：

```text
count < mailboxCapacity → reserve successful
count >= mailboxCapacity → FULL
```

因此 queue slot 與 mailbox 的邏輯容量是兩個不同概念。

## generation

Failure 後 generation 增加，代表新的 Actor instance 世代。它目前主要作為
state identity 與 restart metadata；若未來加入 async callback 或 stale task
防護，可用 generation 拒絕舊世代 callback。
