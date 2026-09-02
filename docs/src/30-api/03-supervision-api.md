# Supervision API

`actor-supervision` 建立在 `ManagedActorRef` 的 factory、restart 與 failure
listener 之上。它不改變 core 的 at-most-once delivery 契約。

## `ChildSpec<M>`

```java
public record ChildSpec<M>(
        String name,
        Supplier<? extends Actor<M>> factory,
        ActorOptions options) { }
```

`name` 用於識別 child，`factory` 在初次建立與 restart 時使用，`options` 決定
該 child 的 mailbox、batch 與 reduction 設定。factory 不應捕捉已失效的 request
scope 或一次性 connection。

## `Supervisor`

```java
Supervisor root = new Supervisor(
        system,
        RestartStrategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(10));

ActorRef<Message> worker = root.spawn(new ChildSpec<>(
        "worker", WorkerActor::new, ActorOptions.defaults()));
```

主要方法：

| API | 說明 |
|---|---|
| `spawn(spec)` | 註冊並建立 direct child |
| `spawnSupervisor(name, strategy)` | 建立預設 5 次／10 秒的 subtree |
| `spawnSupervisor(name, strategy, maxRestarts, window)` | 建立自訂 restart window |
| `children()`／`subtrees()` | 取得目前 topology snapshot |
| `restartCountInWindow()` | 查看本節點的 restart 次數 |
| `stopSubtree()` | 停止本節點與 descendants |
| `close()` | 等同 `stopSubtree()` |

## Restart 策略

`RestartStrategy` 有三種值：

- `ONE_FOR_ONE`：只 restart 發生 failure 的 child。
- `ONE_FOR_ALL`：restart 此 supervisor 下所有 direct children。
- `REST_FOR_ONE`：依註冊順序，restart failure child 及其後續 children。

restart window 是每個 supervisor 節點獨立計算的固定大小 circular timestamp
buffer。超過 `maxRestarts`／`window` 時，該 subtree 會停止，避免 crash loop
演變成 CPU 風暴；上層應透過 termination 或部署監控升級故障。

## Restart 的訊息語意

restart 會建立新的 actor instance：

- 已排入 queue、尚未取出的訊息會保留。
- 已取出且正在處理的 in-flight message 不會重播。
- 外部 side effect 不會 rollback。
- actor 欄位 state 會重新初始化；需要持久化的 state 應放在外部 store。

這個選擇維持 at-most-once，避免 runtime 在不知情況下重複付款、寫入或發布事件。

## `DeathWatch`

```java
DeathWatch watch = new DeathWatch();
watch.watch(observer, worker);
// worker termination 後，observer 收到 new Terminated(worker)
watch.unwatch(observer, worker);
```

`observer` 必須是 `ActorRef<Terminated>`。watch 關係由 `DeathWatch` 管理，動態
topology 在 child 移除時應呼叫 `unwatch`，避免註冊表長期保留關係。

## `CircuitBreaker`

`CircuitBreaker` 用 `CLOSED → OPEN → HALF_OPEN` 保護 actor-backed dependency：

```java
CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(5));
CompletionStage<Response> response = breaker.execute(
        () -> client.ask(request, Duration.ofSeconds(2), Response.class));
```

`allowRequest()` 在 open window 內拒絕；timeout 後只放行一個 half-open probe，
成功回到 closed，失敗重新 open。`CircuitOpenException` 是預期的 overload
訊號，application 應轉換成 fallback 或適當的 HTTP status。

