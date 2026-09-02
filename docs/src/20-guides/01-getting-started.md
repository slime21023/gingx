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

try (ActorSystem system = new ActorSystem()) {
    ActorRef<String> echo = system.spawn(() -> new Actor<>() {
        @Override
        protected void onMessage(String message, ActorContext context) {
            context.reply(message.toUpperCase());
        }
    }, ActorOptions.builder().name("echo").build());

    String result = echo
            .ask("hello", Duration.ofSeconds(2), String.class)
            .toCompletableFuture()
            .join();
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

`ask` 會建立 `CompletionStage`，Actor 使用 `context.reply(value)` 完成它。
timeout 到期後 future 會失敗；它不是同步 RPC，也不保證 handler 一定成功。

## 下一步

- 需要 overload 策略：閱讀[Mailbox 與 Backpressure](03-mailbox-and-backpressure.md)。
- 需要 restart：閱讀[Supervision 實務](05-supervision-guide.md)。
- 需要 HTTP：閱讀[HTTP、JSON 與 TLS](07-http-guide.md)。
