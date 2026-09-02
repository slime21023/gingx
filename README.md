# Preemptive Actor Runtime

Java 25 / Groovy 5 actor runtime managed by Maven. The first production line is a
single-JVM, JVM-only library with a stable 1.0 API; Native Image remains a
non-blocking compatibility lane until GraalVM coverage is available.

## Requirements

- JDK 25
- Maven 3.9.16 or newer
- Groovy 5 is needed only by the optional Groovy adapter
- mdBook 0.5.4 is needed to build the developer documentation

## Developer documentation

The complete documentation is maintained as an mdBook. It is arranged in the
order architecture and internals → application guides → API reference →
operations and contributing:

~~~bash
mdbook build docs
mdbook serve docs
~~~

Read the [documentation source](docs/src/README.md) or open the generated
`target/mdbook/index.html` after a build.

## Build and quality gates

~~~bash
mvn --settings .mvn/settings.xml verify
mvn --settings .mvn/settings.xml -Pjmh -pl actor-benchmarks -am package
java -jar actor-benchmarks/target/actor-benchmarks-1.0.0-SNAPSHOT-all.jar
mvn --settings .mvn/settings.xml -Pstress -pl actor-stress -am test
mvn --settings .mvn/settings.xml -pl actor-tck -am test
~~~

The regular reactor test suite is deterministic. The million-actor memory gate
is opt-in because it requires a dedicated machine:

~~~bash
mvn --settings .mvn/settings.xml -pl actor-stress -am -DrunMillionActors=true test
~~~

The JMH release gate measures the MPSC queue with three producers and one
consumer. The target is at least 50M operations/second with a report containing
P99 and P99.9 latency. The reduction benchmark compares plain and instrumented
loops; the target overhead is below 3% for the selected production budget.

## Runtime contract

~~~java
ActorSystemOptions systemOptions = ActorSystemOptions.builder()
    .defaultActorOptions(ActorOptions.builder()
        .mailboxCapacity(1024)
        .overflowStrategy(MailboxOverflowStrategy.FAIL_FAST)
        .build())
    .shutdownTimeout(Duration.ofSeconds(30))
    .build();

try (ActorSystem system = new ActorSystem(systemOptions)) {
    ActorRef<String> actor = system.spawn(() -> new Actor<>() {
        @Override
        protected void onMessage(String message, ActorContext context) {
            context.reply(message.toUpperCase());
        }
    });

    CompletionStage<String> result = actor.ask("hello", Duration.ofSeconds(2), String.class);
}
~~~

Mailboxes are bounded by default at 1024 messages and use at-most-once
delivery. send returns an explicit SendResult; FAIL_FAST returns FULL, while
drop strategies return DROPPED or ACCEPTED_AFTER_DROP. Messages sent during
shutdown are rejected with SYSTEM_SHUTTING_DOWN or SYSTEM_CLOSED.

ActorSystem.shutdown(Duration) requests cancellation and interruption, waits
until the deadline, and returns a ShutdownReport. close() uses the typed system
default. User code must cooperate with cancellation; the runtime never uses
Thread.stop.

PoisonPill.INSTANCE is a system message that drains preceding messages and
then terminates the target actor. Supervision restarts preserve queued messages
and drop the in-flight message. Crash-loop limits stop the affected supervisor
subtree.

## Modules

- actor-core: VarHandle MPSC queue, packed actor state, virtual-thread
  runtime, lazy mailbox allocation, cancellation, ScopedValue context,
  counters, JFR message events, typed lifecycle options and graceful shutdown.
- actor-groovy: Groovy 5 << / ask DSL, GINQ snapshot queries and the
  @Preemptive local AST transformation. It is an optional JVM extension.
- actor-supervision: OneForOne, OneForAll and RestForOne policies, nested
  supervisor trees, crash-loop protection, DeathWatch and CircuitBreaker.
- actor-http: JDK HttpServer adapter with bounded request bodies, handler
  deadlines, optional TLS and graceful close.
- actor-http-jackson: optional Jackson request/response JSON adapter.
- actor-observability-micrometer: optional Micrometer bridge for core counters.
- actor-tck: runtime contract, fault-injection and lifecycle tests.
- actor-demo: Java-only HTTP demo and Native Image compatibility metadata.
- actor-benchmarks / actor-stress: JMH and opt-in concurrency/memory gates.

## Native Image compatibility lane

The demo is Java-only and carries Native Image metadata. A GraalVM JDK is
required; a Microsoft OpenJDK is intentionally rejected by the native plugin.

~~~bash
mvn --settings .mvn/settings.xml -pl actor-demo -am install
mvn --settings .mvn/settings.xml -Pnative -pl actor-demo org.graalvm.buildtools:native-maven-plugin:compile
~~~

Native compilation is not part of the JVM 1.0 hard release gate. Run it in a
GraalVM-enabled CI/nightly job and review reflection metadata before promoting
a native artifact.

## Internal release checklist

1. Run the Linux x86_64 and Windows x86_64 CI matrix on JDK 25.
2. Run verify, the stress suite, JMH throughput/latency and the opt-in
   million-actor memory gate on dedicated hardware.
3. Run API baseline checks, dependency vulnerability/SBOM checks and review
   JFR/counter smoke output.
4. Publish sources, Javadocs, checksums and signed 1.0.0 artifacts to the
   internal Maven repository.
5. Keep the 0.x prototype migration note separate; 1.0 is the first
   compatibility promise.
