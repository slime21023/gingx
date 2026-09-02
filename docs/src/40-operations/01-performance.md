# 效能評估與品質門檻

效能數字必須和硬體、JDK、OS、JMH 參數與 workload 一起保存。文件中的數字是
可重現基線，不是對所有部署環境的保證。

## 已記錄的 MPSC 基線

測試環境為 Microsoft OpenJDK 25.0.4.1、Windows x86-64；MPSC workload 為
3 producers + 1 single consumer，JMH 使用 2 forks、3 warmups、5 measurements。

| Benchmark | 觀測值 |
|---|---:|
| MPSC total | 62.95M ops/s |
| `offer` | 12.11M ops/s |
| `poll` | 50.84M ops/s |
| total P99 | 約 500 ns |
| total P99.9 | 約 10.19 µs |

P99／P99.9 latency 是短跑觀測值；正式 release 必須在固定 dedicated runner
重新執行並檢查 raw JMH output。

## 執行基準

```text
mvn --settings .mvn/settings.xml -Pjmh -pl actor-benchmarks -am package
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  com.example.actor.bench.MpscQueueBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  com.example.actor.bench.MpscQueueLatencyBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s
```

要比較版本，固定 CPU frequency policy、process affinity、JDK vendor/version、
heap 設定與 background load；每次報告附上 commit、JVM flags、CPU model、OS、
fork、warmup、measurement 與 confidence interval。

## Reduction／preemption 評估

```text
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar \
  PreemptionBenchmark -f 1 -wi 3 -i 5 -w 1s -r 1s -tu s
```

這個測試要分開呈現：

1. counter fast path 的額外成本。
2. budget 歸零時 `Thread.yield()` 的讓步成本。
3. 代表性 actor workload 的鄰近 actor 訊息延遲。

不能只用包含大量 yield 的 smoke run 宣稱 overhead 小於 3%。純 CPU loop 若是
Java 或未經 Groovy AST 編譯，也不會自動具有 preemption。

## 功能與容量驗收

```text
mvn --settings .mvn/settings.xml verify
mvn --settings .mvn/settings.xml -pl actor-tck -am test
mvn --settings .mvn/settings.xml -Pstress -pl actor-stress -am test
mvn --settings .mvn/settings.xml -pl actor-stress -am -DrunMillionActors=true test
```

最後一項需要專用機器，不應放入一般 PR gate。正式報告至少要包含 actor count、
heap／RSS、GC pause、失敗與重啟數、shutdown report，以及 P50/P99/P99.9 message
latency。若測試沒有收集其中一項，應標記為 incomplete，而不是推導出不存在的
品質結論。

## 解讀常見回歸

| 現象 | 優先檢查 |
|---|---|
| throughput 降低 | CPU pinning、JDK、chunk size、producer contention、GC |
| P99 上升但平均值不變 | allocation slow path、CAS retry、carrier oversubscription、yield |
| mailbox 長期佔用上升 | producer rate、`maxBatch`、overflow 結果是否被忽略 |
| 百萬 actor RSS 上升 | lazy mailbox 是否觸發、queue chunk retention、測試 fixture 是否保留 ref |
| shutdown 超時 | handler cancellation checkpoints、阻塞 I/O、未關閉 ingress |

