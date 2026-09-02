# Groovy AST 搶佔引擎

## 編譯期流程

`@Preemptive` 是 source-retention annotation，透過 Groovy AST transformation
在 semantic analysis phase 修改語法樹：

```text
@Preemptive
    ↓
AnnotationNode / MethodNode / ClassNode
    ↓
ClassCodeVisitorSupport
    ↓
for / while / do-while loop
    ↓
ReductionBudget.tickCurrent(budget)
```

方法或 class 被標註後：

1. method body 開頭加入一次 tick。
2. `for`、`while`、`do-while` 的 loop body 開頭加入 tick。
3. node metadata 防止同一 method 被重複 instrument。

## Before / after

原始 Groovy：

```groovy
@Preemptive(budget = 4096)
int sum(int limit) {
    int result = 0
    for (int i = 0; i < limit; i++) {
        result += i
    }
    result
}
```

概念上的 instrumented 結果：

```groovy
int sum(int limit) {
    ReductionBudget.tickCurrent(4096)
    int result = 0
    for (int i = 0; i < limit; i++) {
        ReductionBudget.tickCurrent(4096)
        result += i
    }
    result
}
```

## Bitmask reduction

Budget 為 2 的冪次時：

```text
mask = refill - 1
remaining = remaining - 1
if ((remaining & mask) == 0) preempt
```

fast path 只更新 counter 並執行 bit test；到達邊界時才會執行：

- preemption observer counter。
- cancellation check。
- `Thread.yield()`。

## 使用限制

這不是硬體級 preemption：

- 只有被 `@Preemptive` instrument 的 Groovy code 會自動 checkpoint。
- 未標註的 Java CPU loop 不會自動停止。
- 不可中斷的 native 或 user blocking operation 可能延遲 cancellation。
- `Thread.yield()` 是協作式提示，不是 scheduler 的硬性搶佔命令。

## Budget 選擇

budget 太小會增加 checkpoint 次數；太大則會增加單次不可讓步的 CPU 時間。
選擇時應以「鄰近 Actor 的最大可接受延遲」為準，並用固定硬體的 JMH 與
actor workload 評估，而不是只看微型 loop。
