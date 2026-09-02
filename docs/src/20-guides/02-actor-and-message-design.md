# Actor 與訊息設計

## 一個 Actor 做一件事

建議讓每個 Actor 管理一個清楚的 consistency boundary：

```text
OrderActor    → order lifecycle
InventoryActor → inventory reservation
PaymentActor  → payment state
```

Actor 之間透過 message 溝通，而不是直接共享 mutable field。

## 使用 immutable message

```java
public record ReserveStock(String orderId, int quantity) {
}
```

送出後不要修改 message 內容。Queue 會安全發布 message reference，但 runtime
不會深拷貝物件，也不會替 application 解決 mutable object 的 data race。

## `send` 還是 `ask`

使用 `send` 的情境：

- 不需要立即結果。
- Actor 之間傳遞事件。
- 可接受以 metrics 或後續事件觀察結果。

使用 `ask` 的情境：

- 需要明確 response。
- 需要 timeout boundary。
- 呼叫方能處理 `CompletionStage` failure。

不要在 Actor handler 中無限等待另一個 Actor 的 `ask`，否則容易形成循環等待。

## 處理 side effect

At-most-once runtime 不會自動重播 in-flight message。若 handler 需要呼叫外部
系統，建議帶上 idempotency key：

```java
public record ChargePayment(String requestId, long amountCents) {
}
```

外部系統應以 `requestId` 去重；retry policy 則應由 application 或 supervision
層決定。

## 不要讓單一 message 無限執行

長時間 CPU 工作可以：

1. 切成多個 continuation message。
2. 在 loop 中檢查 `context.cancellation()`。
3. 在 Groovy 使用 `@Preemptive`。
4. 將工作交給專用 bounded executor，再回傳結果。

Virtual Thread 適合大量 blocking task，但不會自動中斷任意 CPU loop。
