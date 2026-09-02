# Supervision 實務

## 建立 Supervisor

```java
try (ActorSystem system = new ActorSystem();
     Supervisor supervisor = new Supervisor(
             system,
             RestartStrategy.ONE_FOR_ONE,
             5,
             Duration.ofSeconds(10))) {

    ActorRef<Integer> worker = supervisor.spawn(new ChildSpec<>(
            "worker",
            () -> new Actor<>() {
                @Override
                protected void onMessage(Integer value, ActorContext context) {
                    process(value);
                }
            },
            ActorOptions.defaults()));
}
```

Actor factory 會在 restart 時重新建立 Actor instance。queued messages 會保留，
已經取出的 in-flight message 不會重播。

## 策略選擇

- `ONE_FOR_ONE`：child 彼此獨立時使用。
- `ONE_FOR_ALL`：children 共享一致性，任一失敗就全部重建。
- `REST_FOR_ONE`：後續 children 依賴前面 children 的狀態時使用。

`REST_FOR_ONE` 依 child 註冊順序決定「後續」範圍。

## Nested supervisor

```java
Supervisor payments = supervisor.spawnSupervisor(
        "payments",
        RestartStrategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(10));
```

Crash-loop 超過限制時，會停止受影響的 supervisor subtree，而不是繼續無限
restart。上層應監控 termination 並決定是否升級故障。

## DeathWatch

```java
ActorRef<Terminated> monitor = system.spawn(() -> new Actor<>() {
    @Override
    protected void onMessage(Terminated event, ActorContext context) {
        recordTermination(event.actor().name());
    }
});

DeathWatch deathWatch = new DeathWatch();
deathWatch.watch(monitor, worker);
```

使用完畢可呼叫 `unwatch`，尤其是動態建立大量 watch relationship 時。

## Supervisor 不是 transaction

Restart 只重建 Actor instance，不會復原外部 database、HTTP side effect 或檔案
操作。這些一致性需求仍需由 application layer 設計。
