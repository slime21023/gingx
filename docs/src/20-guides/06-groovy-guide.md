# Groovy DSL、GINQ 與 Preemptive

## Maven dependency

```xml
<dependency>
    <groupId>com.example.actor</groupId>
    <artifactId>actor-groovy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Operator DSL

`ActorRef` 提供 `leftShift`，因此 Groovy 可以寫：

```groovy
ref << Message.newMessage()
```

它仍然是非同步 send；operator expression 的回傳值是同一個 `ActorRef`，而不是
handler result。

## `@Preemptive`

```groovy
import com.example.actor.groovy.Preemptive

@Preemptive(budget = 4096)
class CpuWorker {
    int calculate(int limit) {
        int result = 0
        for (int i = 0; i < limit; i++) {
            result += i
        }
        result
    }
}
```

AST transformation 會在 method 開頭及 loop body 開頭加入 reduction tick。budget
必須是 2 的冪次。

這個機制只在編譯期 instrument Groovy code；未標註的 Java method 或外部 native
call 不會自動取得同樣的 checkpoint。

## GINQ snapshot query

```groovy
def result = ActorQueries.evenSquares([1, 2, 3, 4])
assert result == [4, 16]
```

GINQ 應該查詢一致的 snapshot 或 immutable collection，不要從其他 thread 直接
掃描 Actor 正在修改的 mutable state。

## `config.groovy`

adapter 的 `config.groovy` 可套用 `@CompileStatic`，但仍應針對 AST transformation
與 dynamic DSL 保留 integration test，因為 static compilation 可能暴露不同的
Groovy type resolution 行為。
