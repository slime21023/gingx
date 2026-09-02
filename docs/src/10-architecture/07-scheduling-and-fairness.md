# Virtual Thread 與排程公平性

## Virtual Thread activation

`ActorSystem` 使用 `newVirtualThreadPerTaskExecutor()`。Actor 不對應一條
長駐平台 thread，而是對應可被提交、完成與重新提交的 activation task。

```text
Actor A mailbox becomes runnable
        │
        ▼
executor.submit(A.runActivation)
        │
        ▼
Virtual Thread
        │ JVM scheduler
        ▼
carrier thread
```

Virtual Thread 適合大量 idle Actor 與 blocking I/O；它不會自動替 CPU-bound
user code 提供安全的任意 instruction 中斷。

## 兩層 fairness

### Message-level fairness

`maxBatch` 限制單次 activation 處理的訊息數量。batch 結束後，如果 state
顯示仍有訊息，Actor 會回到 runnable 並重新提交。

### CPU-level fairness

Groovy `@Preemptive` 在 loop 中加入 reduction tick。budget 到期時執行
`Thread.yield()`，讓 Virtual Thread 回到 scheduler。

這兩者不可互相取代：

```text
maxBatch      控制訊息之間的公平性
reduction     控制單一訊息內的 CPU loop 公平性
```

## Blocking 與 CPU-bound

```text
blocking I/O       → Virtual Thread 通常可讓出 carrier
CPU-heavy handler   → 持續佔用 carrier，需 batch/reduction/cancellation
native call         → 取決於 native API 是否可中斷或讓出
```

Application 不應把長時間計算全部放在未 instrument 的單一 message handler
中；應拆成多個 message、使用明確 checkpoint，或使用 `@Preemptive` Groovy code。

## 排程防重入

Producer 只負責 reserve 與必要時 schedule；真正啟動必須經過 `tryStart()`。
這讓「多 Producer 同時送入第一筆訊息」不會產生多個並行 drain loop。
