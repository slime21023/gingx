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

Behaviour that changed while hardening the runtime for 1.0:

- an ask future completes after the handler returns rather than inside
  `ActorContext.reply(value)`, so a dependent stage no longer observes the
  `ActorContext` or inherits the `TraceContext` of the replying actor;
- the mailbox is a fixed-capacity ring sized from `mailboxCapacity`. Storage no
  longer grows with the number of messages a mailbox has carried, and the
  removed `MpscChunkedArrayQueue` is replaced by `MpscBoundedArrayQueue`;
- an activation abandons and reschedules itself when a producer reserved a
  mailbox slot it has not published, instead of spinning inside the queue. The
  new `reservationStalls` counter records this;
- `ActorState.requestStop()` returns the lifecycle it observed and
  `ActorState.restart()` returns a `RestartResult`, refusing a cell that a
  running activation owns; an owning activation uses `restartFromOwner()`;
- `ManagedActorRef` gained `suspend()` and `resume()`, and a suspended actor no
  longer starts activations until it is resumed;
- `ActorSystemOptions.deadLetterListener(...)` reports undelivered messages.

Applications should replace unbounded body reads with
ActorHttpServer.readBody(exchange, options), handle every SendResult, and use
ActorSystem.shutdown(Duration) during deployment termination.
