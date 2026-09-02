# 0.x → 1.0 Migration

0.x 是 prototype，沒有 binary 或 source compatibility 承諾；1.0 開始凍結 Java
API 與下列預設行為：

- mailbox capacity 預設為 1024，overflow policy 預設為 `FAIL_FAST`。
- delivery 是 at-most-once；queued message 可在 supervised restart 後保留，
  in-flight message 不會 replay。
- cancellation 是協作式，activation 可能收到 interrupt。
- shutdown 有 deadline；transition 中的 `send` 回傳明確的
  `SYSTEM_SHUTTING_DOWN` 或 `SYSTEM_CLOSED`。
- Groovy、Jackson、Micrometer 與 Native Image 都是圍繞 Java-only core/JVM release
  的 optional extension。

## 建議調整

1. 將無界 request body 讀取改成 `ActorHttpServer.readBody(exchange, options)`。
2. 逐一處理所有 `SendResult`，不要假設 send 一定成功。
3. 部署終止時先停止 ingress，再呼叫 `ActorSystem.shutdown(Duration)` 並檢查
   `ShutdownReport.terminated()`。
4. 對 `ask` 設定 timeout，對可重試 side effect 加入 idempotency key。
5. 將 restart 所需的 actor factory 改成可重建形式，並將 durable state 移至外部
   store。

舊 prototype 的無限 mailbox、同步 RPC 假設或 handler 內強制終止做法，都不應直接
帶入 1.0 production profile。

