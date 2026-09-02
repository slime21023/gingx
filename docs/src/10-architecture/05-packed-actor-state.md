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
IDLE + count>0 --scheduleIfIdleWithWork()----> RUNNABLE + scheduled
```

`requestStop()` 會回傳它觀察到的前一個 lifecycle，`scheduleIfIdleWithWork()`
則負責把 `fail()` 之後仍留有 queued message 的 cell 重新排程，避免
FailureListener 既不重啟也不停止時訊息靜默滯留。

## Activation ownership

packed state 是「誰擁有這個 cell」的唯一真相。這一點很重要，因為
`runActivation()` 是先做 `tryStart()`（狀態已成為 `RUNNING`），之後才把自己
登記到 `activationThread`：

```text
tryStart() 成功 ──> RUNNING ──> [ 窗口 ] ──> activationThread = current
```

在這個窗口內 `activationThread` 仍是 null。若外部的 `stop()` 或 `restart()`
以 `activationThread == null` 判斷「沒有 activation 在跑」，就會誤判並可能
提交第二個 activation，或在別的執行緒排空 mailbox——而 mailbox 是 single
consumer，兩個消費者不是排序問題，而是資料結構損毀。

因此外部呼叫者一律以 lifecycle 判斷：

| 觀察到的 lifecycle | 外部呼叫者的行為 |
|---|---|
| `RUNNING` | 記錄請求並取消／中斷，終止與重啟交給擁有者 |
| 其他 | 自行終止或重啟 |

`restart()` 對 `RUNNING` 回傳 `REFUSED`；正在離開的 activation 自己則使用
`restartFromOwner()`，它接受 `RUNNING`。把 cell 保持在 `RUNNING` 直到這一次
原子轉移，可確保中間沒有其他 activation 能啟動——先釋放再重啟的寫法會留下
這個空隙。

同一條規則也適用於終止排空：只有擁有 activation 的執行緒能消費 mailbox。
若其他執行緒在 cell 執行中終止它，排空工作會留給擁有者在離開時完成。

## suspended

`tryStart()` 會拒絕 suspended cell。由於 `scheduled` 旗標仍為 true，
`reserveMessage()` 不會再次排程，因此 `resume()` 必須自行補送被吞掉的那次
activation；它回傳是否需要提交。

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
