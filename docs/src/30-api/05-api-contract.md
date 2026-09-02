# API Contract 與文件規範

本章是使用者在升級、封裝與 code review 時應遵循的公共契約。

## Delivery contract

runtime 提供 at-most-once message processing：一則 message 最多被一個 actor
activation 取出並交給 handler 一次。以下情形不構成成功處理：

- `send` 回傳 `FULL`、`DROPPED`、`TERMINATED` 或 system rejection。
- handler 在 message 中途失敗；in-flight message 不會因 restart 自動 replay。
- `ask` timeout；future 失敗不代表 side effect 一定沒有發生。

需要 retry、deduplication、transactional outbox 或 exactly-once effect 時，必須
由 application／外部儲存層實作 idempotency key 與一致性邊界。

## Lifecycle contract

`ActorRef` 的控制操作是可重複呼叫的，但 application 不應依賴未公開的內部
state bit。正常順序是 `NEW → IDLE/RUNNABLE → RUNNING → IDLE`，停止後進入
`STOPPING → TERMINATED`；failure、cancel 與 system shutdown 會在相同終止
邊界上收斂。

`ActorContext`、`ScopedValue` 與 context reply 僅保證在目前 handler activation
範圍內有效。

## Thread-safety contract

- `ActorRef`、`ActorSystem` 的 public lifecycle／send surface 可由多執行緒呼叫。
- 同一 actor 的 `onMessage` 具有 single-threaded invariant。
- actor-owned mutable state 不應在 actor 外直接讀寫。
- `ActorContext` 不應跨 activation 保存。
- extension adapter 的 route registration 應在 `start()` 前完成。

## Compatibility contract

1.0 API 的 compatibility scope 是單 JVM、JDK 25、Maven artifact。`actor-groovy`
與各 extension module 是 optional；新增整合不應讓 `actor-core` 依賴它們。
Native Image 是 compatibility lane，未列入 JVM 1.0 hard gate。

發布前應產生 sources、Javadocs、SBOM，執行 API baseline、dependency vulnerability
與簽章驗證。公共類別、方法、enum 值與 exception 語意變更，必須同步更新本章、
高階指南與相應 TCK。

## 文件規範

每個公開 API 的文件至少回答：

1. 建立與最小使用方式。
2. thread-safety、lifecycle 與 memory／deadline 限制。
3. 成功與拒絕／失敗結果。
4. 是否是 core guarantee、extension behavior 或 implementation detail。
5. 對應的測試、benchmark 或 source class。

