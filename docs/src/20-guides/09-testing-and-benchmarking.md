# 測試與效能評估

## TestKit

`actor-testkit` 是 test-scoped 模組，用來取代「睡一段時間再檢查旗標」的
測試寫法。三個工具：

```xml
<dependency>
    <groupId>com.example.actor</groupId>
    <artifactId>actor-testkit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### `TestProbe` — 可斷言的 mailbox

```java
try (ActorSystem system = new ActorSystem();
     TestProbe<String> probe = TestProbe.create(system)) {

    ActorRef<String> subject = system.spawn(() -> new Actor<String>() {
        @Override
        protected void onMessage(String message, ActorContext<String> context) {
            probe.ref().send(message.toUpperCase());
        }
    }, ActorOptions.defaults());

    subject.send("a");
    probe.expectMessage("A", Duration.ofSeconds(5));
    probe.expectNoMessage(Duration.ofMillis(50));
}
```

每個期望都有 deadline，失敗訊息會列出實際收到的內容。

### `awaitQuiescent` — 取代 sleep 輪詢

```java
for (int i = 0; i < 1_000; i++) counter.send(i);
ActorTestKit.awaitQuiescent(system, Duration.ofSeconds(5));
assertEquals(1_000, processed.get());
```

Quiescent 的定義是「沒有 actor 正在執行，也沒有 actor 還有 queued
message」。它在**送訊息的一方已經送完之後**才有意義；超時的失敗訊息會列出
仍在忙碌的 actor 與其 mailbox 長度。

`ActorSystem.awaitQuiescent(Duration)` 也可用於正式環境，例如在計畫性關機
之前先排空。

### `TestScheduler` — 虛擬時間

```java
TestScheduler scheduler = new TestScheduler();
try (ActorSystem system = new ActorSystem(
        ActorSystemOptions.builder().scheduler(scheduler).build())) {

    session.send(new Activity());                       // 布下 5 分鐘 timer
    ActorTestKit.advanceAndSettle(scheduler, system,
            Duration.ofMinutes(5), Duration.ofSeconds(5));
    probe.expectMessage(new Expired(), Duration.ofSeconds(5));
}
```

測試 5 分鐘的逾時不需要等 5 分鐘，也不依賴時序。`advanceAndSettle` 先推進
虛擬時間執行到期任務，再等待因此產生的訊息被處理完畢。

### 其他

- `ActorTestKit.awaitTerminated(ref, timeout)`：等待終止通知，而非輪詢
  `isTerminated()`。
- `ActorTestKit.awaitCondition(condition, timeout, description)`：測試自有
  狀態的條件；仍是取樣，但至少集中在一個有 deadline 與診斷訊息的地方。

## 一般測試

```text
mvn --settings .mvn/settings.xml -U test
```

這會執行 core、HTTP、Groovy、supervision、extension、stress 與 TCK 測試。

## TCK

```text
mvn --settings .mvn/settings.xml -pl actor-tck -am test
```

TCK 驗證 lifecycle、PoisonPill、shutdown rejection 與 fault injection。它是
runtime contract test，不是完整 application test。

## Stress 與 memory gate

一般 stress：

```text
mvn --settings .mvn/settings.xml -Pstress -pl actor-stress -am test
```

百萬 Actor 測試需要專用機器：

```text
mvn --settings .mvn/settings.xml -pl actor-stress -am -DrunMillionActors=true test
```

不要在一般 CI runner 無限制開啟，並應保存 heap、JDK、CPU 與 OS 資訊。

## JMH

```text
mvn --settings .mvn/settings.xml -Pjmh -pl actor-benchmarks -am package
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  MpscQueueBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s
```

正式結果必須固定：

- CPU model 與 core topology。
- JDK build 與 JVM flags。
- fork、warmup、measurement。
- producer/consumer thread configuration。
- P99/P99.9 latency 計算方式。

Preemption benchmark 必須把 counter fast path 與 `Thread.yield()` 讓步成本分開，
不能用微型 loop 的單次結果直接宣稱 `<3%` overhead。
