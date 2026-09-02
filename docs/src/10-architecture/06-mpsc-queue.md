# MPSC Queue 與 Java Memory Model

`MpscChunkedArrayQueue<E>` 是 multiple-producer, single-consumer queue。每個
Actor 只有一個 activation consumer，但任意 application thread 都可能呼叫
`send()`。

## Queue 結構

```text
producerIndex ──► chunk 0 ──► chunk 1 ──► chunk 2 ──► ...
consumerIndex ──►   ^
                 single consumer
```

Chunk 預設有 1024 個 slot，大小必須是 2 的冪次，offset 可用 mask 計算。
Producer 與 Consumer counter 之間加入 padding，降低 false sharing。

## Producer publish

Producer 的概念流程：

```text
index = producerIndex CAS increment
chunk = locate(index)
ARRAY_HANDLE.setRelease(chunk.elements, offset, element)
```

CAS reservation 讓每個 producer 取得唯一的 global position；release store
則將 element 及其先前的初始化寫入發布給 Consumer。

## Consumer read

Consumer 的概念流程：

```text
index = consumerIndex
published = producerIndex
等待 element 可見
element = ARRAY_HANDLE.getAcquire(...)
清除 slot
consumerIndex.setRelease(index + 1)
```

Consumer 的 acquire load 與 Producer 的 release store 配對，建立 Java Memory
Model 的 happens-before 發布關係。

## Reservation gap

Producer 可能在 reservation 後、publish 前被暫停：

```text
Producer A: reserve index 10 ── paused
Producer B: reserve index 11 ── publish complete
Consumer:   等待 index 10
```

Consumer 不會跳過 index 10 讀取 index 11，因此保留 reservation order，但
代價是可能 spin-wait。這是此類 queue 必須在 scheduler 與壓測中觀察的特性。

## allocation 語意

queue 的 slot 操作不會為每筆訊息建立 node；chunk 只在跨越邊界時建立。然而
Actor runtime 仍可能為 `Envelope`、`ActorContext` 或 `ask` future 配置物件。
因此「queue steady-state allocation-free」不等於「整條訊息路徑 zero GC」。

## 容量與回收限制

ActorState 負責邏輯 mailbox capacity；queue 本身是 storage。現行 queue 的
chunk linked list 沒有完整 recycling，長時間大量流量可能讓已使用 chunk
仍由 head link 保持可達。正式 production 前應以 chunk reclamation 或可重用
segment 設計解決這個生命週期問題。
