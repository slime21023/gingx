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

API changes that make the runtime usable for a full application:

- `ActorContext` is now `ActorContext<M>` and `self()` returns `ActorRef<M>`,
  so an actor can send to itself without an unchecked cast. Handlers change
  from `onMessage(M, ActorContext)` to `onMessage(M, ActorContext<M>)`;
- `ask(Duration, replyTo -> message)` is the typed ask. The request carries an
  `ActorRef<R> replyTo`, so both directions are checked at compile time and the
  handler answers with a normal send. `ask(Class, Duration, factory)` states
  the reply type when the call is chained. The older `ask(message, timeout)`
  forms and `ActorContext.reply(value)` are deprecated for removal;
- `ActorContext.timers()` provides keyed timers. Timers belong to the cell and
  are cancelled on restart and on termination, and a message from a replaced or
  cancelled timer is discarded before it reaches the handler;
- `ActorSystemOptions.scheduler(...)` supplies the time source, which is how
  timers are tested without wall-clock delays;
- `ActorContext.stash()` and `unstashAll()` defer and redeliver messages. The
  stash is bounded by `ActorOptions.stashCapacity` and overflow throws
  `StashOverflowException`;
- `ActorContext.spawnChild(...)` creates an actor whose lifetime is contained
  in its parent: stopping a parent stops its subtree;
- `ActorSystem.awaitQuiescent(Duration)` waits until no actor is running or
  holds queued messages, which also serves as a drain before a planned
  shutdown;
- `actor-testkit` is a new test-scoped module: `TestProbe`, `TestScheduler`
  virtual time and deadline-bounded waits.

Applications should replace unbounded body reads with
ActorHttpServer.readBody(exchange, options), handle every SendResult, and use
ActorSystem.shutdown(Duration) during deployment termination.
