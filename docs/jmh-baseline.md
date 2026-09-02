# JMH baseline

測試環境：Microsoft OpenJDK 25.0.4、Windows 11 x86-64、20 個邏輯核心。這是
`1.0.0-SNAPSHOT` 的可重現基準，量測對象是定容 ring `MpscBoundedArrayQueue`。
正式發布前仍須在固定 CPU、JDK、OS 與 JMH 參數的 dedicated runner 重跑並保存
raw output——這台是一般開發機，數字可比較但不足以當作發布憑證。

執行：

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar com.example.actor.bench.MpscQueueBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s
```

結果（MPSC group：3 producer + 1 single consumer，ring capacity 65536）：

| Benchmark | Score | Error |
| --- | ---: | ---: |
| `mpsc` total | 68.13M ops/s | ± 15.56M |
| `offer` | 13.81M ops/s | ± 1.18M |
| `poll` | 54.32M ops/s | ± 16.59M |

`mpsc` total 高於 50M ops/s 的發布門檻。與換掉 queue 之前的 chunked
linked-list 相比（total 62.95M、offer 12.11M、poll 50.84M），定容 ring 略快，
少掉了 chunk 定位與 `producerChunk` hint 的 CAS 競爭。

## 兩個必要的解讀限定

**1. 這個負載下 ring 從未滿載，所以數字可與舊實作直接比較。**

有界 queue 的 `offer` 會在滿載時回傳 false，原則上可能讓失敗的 offer 灌水。
以相同形狀（3 producer、1 consumer、capacity 65536）實測 2 秒：

```text
offers accepted : 28,652,203
offers rejected : 0  (0.0000% of offers)
```

Consumer 的速度遠高於三個 producer 的合計，佇列因此幾乎恆空，沒有任何一次
背壓發生。

**2. `poll` 的 ops/s 不等於「每秒消費的訊息數」。**

同一次量測顯示：

```text
polls with item : 28,652,194
polls on empty  : 44,554,987  (60.9% of polls)
```

六成的 `poll` 打在空佇列上並提早返回，那是一條比實際取件便宜得多的路徑。
`poll` 的分數混合了這兩種操作。舊 baseline 也有同樣性質但未載明；比較歷史
數字時必須確認 producer/consumer 的相對速度沒有改變，否則兩個數字量的不是
同一件事。

## Latency

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar com.example.actor.bench.MpscQueueLatencyBenchmark -f 1 -wi 0 -i 1 -w 1s -r 1s
```

短跑（1 fork、無 warmup、1 秒）觀測值：

| Percentile | total | offer | poll |
| --- | ---: | ---: | ---: |
| p50 | 200 ns | 200 ns | 100 ns |
| p99 | 800 ns | 900 ns | 300 ns |
| p99.9 | 14.49 µs | 14.99 µs | 6.80 µs |
| p99.99 | 73.78 µs | 78.77 µs | 63.62 µs |
| max | 216.83 µs | 216.83 µs | 163.07 µs |

先前記錄的短跑值是 p99 約 500 ns、p99.9 約 10.19 µs。這個組態沒有 warmup、
只有一個 fork 與一秒量測，尾端百分位受 JIT 與 OS 排程主導，**不應據此判定
迴歸**。正式 gate 必須固定 CPU、JDK、OS、fork 與 warmup/measurement 參數後
再比較。

## Reduction smoke run

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  PreemptionBenchmark -f 1 -wi 1 -i 1 -w 1 -r 1 -tu s
```

這個 benchmark 只驗證 instrumented loop 可執行，不能直接用於宣稱「低於
3%」；正式 overhead gate 必須使用代表性 actor 工作負載，並把讓步成本與
純 counter fast path 分開報告。
