# MPSC Queue 與 Java Memory Model

`MpscBoundedArrayQueue<E>` 是 multiple-producer, single-consumer queue。每個
Actor 只有一個 activation consumer，但任意 application thread 都可能呼叫
`send()`。

## 為什麼是定容 ring

`ActorOptions.mailboxCapacity` 被限制在 1..65535，也就是說 runtime 從來不
需要無界 queue。既然容量恆有上界，就以單一固定陣列承載：

```text
capacity = ceilingPowerOfTwo(mailboxCapacity)

        producerIndex ─┐
   ┌───┬───┬───┬───┬───▼───┬───┬───┐
   │   │   │ e │ e │       │   │   │   一次配置，永不重建
   └───┴───┴─▲─┴───┴───────┴───┴───┘
             └─ consumerIndex
```

Backing array 在建構時配置一次，之後不再產生任何 node、chunk 或 link。因此
一個已經承載過任意數量訊息的 queue，其記憶體佔用恆等於初始值。這是刻意
取代先前 chunked linked list 的設計：後者的 head link 會讓每一個曾經配置過
的 chunk 永久可達，記憶體隨「累計訊息數」而非「同時在信箱的訊息數」成長。

Mailbox 仍是 lazy 配置，第一則訊息到達時才建立，所以大量 idle Actor 不會
付出這筆記憶體。

## Producer publish

```text
index = producerIndex，先確認 index < producerLimit
若已達 limit：limit = consumerIndex + capacity，仍不足則回傳 false（FULL）
index = producerIndex CAS increment
ARRAY_HANDLE.setRelease(elements, index & mask, element)
```

`producerLimit` 是 consumer 位置的快取。Producer 熱路徑不必每次讀取
consumer 的 cache line，只在跨越 limit 時同步一次。多個 producer 競寫這個
快取時可能寫入較舊的值，但 limit 只是保守提示，落後只會多一次 consumer
讀取，不會讓 producer 越過容量。

## Consumer read

```text
index = consumerIndex
若 index >= producerIndex → 空，回傳 null
element = ARRAY_HANDLE.getAcquire(elements, index & mask)
若 element == null → reservation gap，回傳 null
清除 slot
consumerIndex.setRelease(index + 1)
```

Consumer 的 acquire load 與 Producer 的 release store 配對，建立 Java Memory
Model 的 happens-before 發布關係。

## Slot 重用的安全性

Producer 只有在 `index < consumerIndex + capacity` 成立時才寫入
`index & mask`。而 consumer 是**先清空 slot、再 release consumerIndex**：

```text
consumer:  ARRAY_HANDLE.set(slot, null)      ──┐ release
           consumerIndex.setRelease(index+1) ──┘
producer:  consumerIndex.getAcquire()        ──┐ acquire
           ARRAY_HANDLE.setRelease(slot, e)  ──┘
```

因此 producer 通過容量檢查時，該 slot 必然已是 null，不可能覆蓋尚未被消費
的元素。這條 happens-before 是整個定容設計的地基。

## Reservation gap

Producer 可能在 reservation 後、publish 前被暫停：

```text
Producer A: reserve index 10 ── paused
Producer B: reserve index 11 ── publish complete
Consumer:   index 10 尚未發佈
```

Consumer 不會跳過 index 10 讀取 index 11，因此保留 reservation order。但
**`poll()` 不會自旋等待**：它回傳 `null`，並由 `hasUnpublishedReservation()`
區分「空」與「有未發佈的保留」。等待策略屬於 activation 迴圈，見
[排程與公平性](07-scheduling-and-fairness.md)。

把等待留在 queue 內是危險的：`Thread.onSpinWait()` 不是 blocking point，
virtual thread 不會 unmount，也不理會 `interrupt()`，因此一個永遠不發佈的
producer 會釘死一條 carrier thread 並使 `shutdown()` 的 cancellation 與
interruption 完全失效。

## 容量與 ActorState 的關係

ActorState 負責邏輯 mailbox capacity，queue 則自我約束物理容量：

```text
ring capacity = ceilingPowerOfTwo(mailboxCapacity) >= mailboxCapacity
ActorState    保證 count <= mailboxCapacity
count         恆 >= queue 中的實際元素數（reserve 早於 offer，release 晚於 poll）
```

所以 `offer()` 在目前的接線下永不回傳 false。它仍被處理，使未來若改變
sizing，行為會退化成背壓而不是無聲覆寫。

## allocation 語意

queue 的 slot 操作不會為每筆訊息建立 node，且沒有任何跨邊界配置。然而
Actor runtime 仍可能為 `Envelope`、`ActorContext` 或 `ask` future 配置物件。
因此「queue steady-state allocation-free」不等於「整條訊息路徑 zero GC」。
