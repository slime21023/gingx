# JMH baseline

> **這份數據已作廢，必須重新量測。** 它是以先前的 chunked linked-list queue
> 取得的；該 queue 已被定容 ring `MpscBoundedArrayQueue` 取代。語意也隨之
> 改變：`offer` 現在會在滿載時回傳 false 形成背壓，而不是無界成長。發布
> gate 前必須用新實作重跑並更新下表。

測試環境：Microsoft OpenJDK 25.0.4.1、Windows x86-64。以下是
`1.0.0-SNAPSHOT` release candidate 的可重現基準；正式發布前仍須在固定
CPU、JDK、OS 與 JMH 參數的 dedicated runner 重跑並保存 raw output。

執行：

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar com.example.actor.bench.MpscQueueBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s
```

結果（MPSC group：3 producer + 1 single consumer）：

| Benchmark | Score | Min | Max |
| --- | ---: | ---: | ---: |
| `mpsc` total | 62.95M ops/s | 50.67M ops/s | 77.86M ops/s |
| `offer` | 12.11M ops/s | 11.08M ops/s | 12.62M ops/s |
| `poll` | 50.84M ops/s | 38.15M ops/s | 66.78M ops/s |

Latency 分佈由 `MpscQueueLatencyBenchmark` 的 JMH `SampleTime` 模式產生，
短跑（1 fork、無 warmup、1 秒）觀測到 total p99 約 500 ns、p99.9 約
10.19 µs；正式 gate 應固定 CPU、JDK、OS、fork、warmup/measurement 參數後
再比較回歸。

## Reduction smoke run

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  PreemptionBenchmark -f 1 -wi 1 -i 1 -w 1 -r 1 -tu s
```

這個 benchmark 只驗證 instrumented loop 可執行，不能直接用於宣稱「低於
3%」；正式 overhead gate 必須使用代表性 actor 工作負載，並把讓步成本與
純 counter fast path 分開報告。
