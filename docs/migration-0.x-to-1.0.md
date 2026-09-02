# Migration from the 0.x prototype to 1.0

The 0.x line was a prototype and did not promise binary or source compatibility.
The 1.0 line freezes the Java API and documents the following defaults:

- mailbox capacity is 1024 and the default overflow policy is FailFast;
- delivery is at-most-once; a message already executing is not replayed after
  a failure, while queued messages survive a supervised restart;
- cancellation is cooperative and may interrupt the activation thread;
- shutdown is deadline-bounded and sends during the transition return an
  explicit SYSTEM_SHUTTING_DOWN or SYSTEM_CLOSED result;
- Groovy, Jackson, Micrometer and Native Image support are optional extensions
  around the Java-only core/JVM release.

Applications should replace unbounded body reads with
ActorHttpServer.readBody(exchange, options), handle every SendResult, and use
ActorSystem.shutdown(Duration) during deployment termination.
