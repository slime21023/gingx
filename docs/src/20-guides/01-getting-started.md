# 快速開始

## Maven dependency

最小使用只需要 `actor-core`：

```xml
<dependency>
    <groupId>com.example.actor</groupId>
    <artifactId>actor-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

專案要求 JDK 25，並使用 Maven reactor 管理版本。

## 建立 ActorSystem

```java
import com.example.actor.Actor;
import com.example.actor.ActorContext;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.SendResult;

import java.time.Duration;

record Echo(String value, ActorRef<String> replyTo) {}

try (ActorSystem system = new ActorSystem()) {
    ActorRef<Echo> echo = system.spawn(() -> new Actor<Echo>() {
        @Override
        protected void onMessage(Echo message, ActorContext<Echo> context) {
            message.replyTo().send(message.value().toUpperCase());
        }
    }, ActorOptions.builder().name("echo").build());

    CompletionStage<String> result =
            echo.ask(Duration.ofSeconds(2), replyTo -> new Echo("hello", replyTo));
}
```

`ActorSystem` 關閉時會停止 Actor、取消 active activation，並等待設定的
shutdown timeout。user code 應配合 cancellation，不要依賴強制終止。

## `send` 與 `ask`

```java
SendResult result = echo.send("fire-and-forget");
```

`send` 立即回傳 `SendResult`，application 必須處理 `FULL`、`DROPPED`、
`TERMINATED` 與 system shutdown rejection。

`ask` 讓請求自己攜帶回覆位址：factory 收到 `ActorRef<R> replyTo`，handler 用
一般的 `replyTo.send(...)` 回覆。因此請求與回覆兩個方向都在編譯期檢查，
handler 也能從訊息型別看出是否需要回覆。

Java 由賦值目標推斷回覆型別；若要在呼叫鏈中使用，改用寫明型別的多載：

```java
ref.ask(Balance.class, Duration.ofSeconds(2), replyTo -> new GetBalance("acc-1", replyTo))
   .thenApply(Balance::amount);
```

Timeout 到期後 future 會失敗；訊息被 mailbox 拒絕時 future 也會立刻失敗，
不會永久等待。它不是同步 RPC，也不保證 handler 一定成功。

較舊的 `ask(message, timeout)` 與 `context.reply(value)` 仍可使用，但已標記
為 deprecated：回覆方向沒有型別，handler 也無法從型別得知是否該回覆。

## 下一步

- 需要 overload 策略：閱讀[Mailbox 與 Backpressure](03-mailbox-and-backpressure.md)。
- 需要 restart：閱讀[Supervision 實務](05-supervision-guide.md)。
- 需要 HTTP：閱讀[HTTP、JSON 與 TLS](07-http-guide.md)。
- 需要 timer、stash 或 child actor：閱讀
  [Timer、Stash 與 Child Actor](10-timers-stash-and-children.md)。
- 要寫測試：閱讀[測試與效能評估](09-testing-and-benchmarking.md)的 TestKit 一節。
