# Timer、Stash 與 Child Actor

這三項是把單一 handler 擴充成真實 actor 應用時最常需要的能力。它們都由
`ActorContext<M>` 提供，因此只能在 `onMessage` 內使用。

## `ActorContext<M>` 是泛型的

`context.self()` 回傳 `ActorRef<M>`，所以 actor 可以對自己送訊息：

```java
protected void onMessage(Command message, ActorContext<Command> context) {
    context.self().send(new Command.Continue(remaining - 1));
}
```

## Timer

Timer 以 key 識別，不是以 handle 識別。用同一個 key 再次啟動會取代前一個，
因此「重設逾時」天然冪等：

```java
protected void onMessage(Session message, ActorContext<Session> context) {
    switch (message) {
        case Session.Activity ignored ->
                context.timers().startSingleTimer("idle", new Session.Expire(), Duration.ofMinutes(5));
        case Session.Expire ignored -> {
            context.timers().cancel("idle");
            close();
        }
    }
}
```

### 必須知道的四件事

1. **取代或取消之後仍在路上的訊息會被丟棄。** Timer 訊息帶著它被排程時的
   generation；到達時若 generation 不再相符，runtime 會在交給 handler 之前
   丟棄它並回報 dead letter。沒有這一層，keyed timer 會送出幽靈訊息。
2. **Timer 訊息就是一般訊息。** 它受 mailbox 容量約束；mailbox 滿載時會被
   丟棄並回報 dead letter，而不是阻塞 scheduler 執行緒。Timer 不保證送達。
3. **Timer 屬於 actor cell，不屬於 actor instance。** restart 與 terminate
   都會取消所有 timer，新的 instance 不會繼承舊 instance 布下的 timer。
4. **只有 actor 自己能操作自己的 timer**，因為 `timers()` 只能從
   `ActorContext` 取得。

### 時間來源可替換

預設是一條 daemon 執行緒的 `SystemScheduler`。測試時改用虛擬時間：

```java
TestScheduler scheduler = new TestScheduler();
ActorSystem system = new ActorSystem(
        ActorSystemOptions.builder().scheduler(scheduler).build());
```

見[測試與效能評估](09-testing-and-benchmarking.md)。

## Stash

`stash()` 把「目前這則訊息」延後，`unstashAll()` 把延後的訊息排到 mailbox
其餘訊息之前重新投遞。典型用途是「初始化完成前先擱置工作」：

```java
private boolean ready;

protected void onMessage(Request message, ActorContext<Request> context) {
    if (message instanceof Request.Ready) {
        ready = true;
        context.unstashAll();
        return;
    }
    if (!ready) {
        context.stash();
        return;
    }
    handle(message);
}
```

### 語意

| 項目 | 行為 |
|---|---|
| 順序 | 依 stash 的順序重新投遞，且排在後續 mailbox 訊息之前 |
| 容量 | `ActorOptions.stashCapacity`，預設等於 `mailboxCapacity` |
| 溢位 | 丟 `StashOverflowException`，actor 依一般失敗路徑處理 |
| 容量佔用 | 被 stash 的訊息**不佔** mailbox 容量，unstash 時需重新取得 |
| ask | 被 stash 的 ask，其 future 保持未完成，直到訊息真正被處理 |
| restart | stash 清空，訊息進 dead letter |

第四點的後果要留意：`unstashAll()` 時 mailbox 可能已滿，個別訊息會被拒絕
並回報 dead letter。一個 actor 的最大暫存量是
`mailboxCapacity + stashCapacity`。

Stash 有界是刻意的：mailbox 既然有界，沒有理由讓延後緩衝無界——那是等待
特定流量觸發的 OOM。

## Child actor

`spawnChild` 建立生命期被包含在自己之內的 actor：

```java
ActorRef<Task> worker = context.spawnChild(
        () -> new WorkerActor(config),
        ActorOptions.builder().name("worker").build());
```

- 停止 parent 會**遞迴**停止整個 subtree，因此 subtree 不會比建立它的 actor
  活得久。
- 終止是「請求」而非「等待」：每個 child 在自己的 activation 上終止，parent
  不會阻塞 carrier 等待它。因此 parent 可能先於 child 完成終止。
- Child 的 `name()` 會帶上 parent 前綴，便於在 metrics 與 JFR 中辨識。
- 終止的 child 會自動從 parent 脫離，`context.childCount()` 反映仍存活的
  child 數。

### 這是生命期，不是監督策略

`spawnChild` 提供的是**包含關係**：child 不會比 parent 活得久。它不決定
child 失敗時要重啟還是停止——那屬於 `actor-supervision` 的
`Supervisor` 與 `RestartStrategy`。兩者可以並用：用 `spawnChild` 表達結構，
用 Supervisor 表達失敗策略。
