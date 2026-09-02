# 測試與效能評估

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
