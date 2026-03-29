---
id: jetstream
title: JetStream
---

JetStream is the persistence layer of NATS. Where Core NATS delivers messages at-most-once and forgets them, JetStream stores messages in server-side **streams** - named logs that capture every message published to a set of subjects. **Consumers** are named cursors into a stream; each consumer tracks its position independently and the server remembers it across restarts. This gives you at-least-once delivery by default, exactly-once delivery with message deduplication, and the ability to replay historical messages from any point.

## Publishing

`JetStream#publish` sends a message and waits for a server acknowledgement before returning. The ack confirms that the message was received, stored, and assigned a sequence number within the stream. This is fundamentally different from Core NATS `Nats#publish`, which returns immediately with no delivery confirmation.

Use `JetStream#publish` whenever messages must survive a server restart or client disconnect - order processing, financial events, audit logs, or any data that must not be silently dropped. The stream must exist before publishing; if no stream matches the subject, `JetStream#publish` fails with `NatsError.JetStreamPublishFailed`.

A basic publish returns a `PublishAck` with the stream name and the sequence number the server assigned:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val publishBasic: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      ack <- js.publish(subject"orders.new", "order-123")
      _   <- ZIO.debug(s"Stored in ${ack.stream} at seq ${ack.seqno}")
    } yield ()
  }
```

### Exactly-once delivery

NATS JetStream supports exactly-once delivery through message deduplication. Assign a stable `messageId` via `PublishOptions` and the server silently discards any duplicate within the stream's deduplication window. If a network failure causes your client to retry a publish, the second copy is dropped server-side - the `PublishAck.isDuplicate` flag tells you which one was the original.

Use this when your publisher may retry on failure and the downstream operation is not idempotent - payment captures, inventory decrements, or any event where processing twice would cause incorrect state:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val exactlyOnce: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    js.publish(
      subject"orders.new",
      "order-123",
      JsPublishParams(options = Some(PublishOptions(messageId = Some("order-42"))))
    ).unit
  }
```

### Async publish

`JetStream#publishAsync` returns immediately with a `Task[PublishAck]` that resolves when the server confirms. Use it when you need to send a large number of messages concurrently and collect the acks separately - the server can pipeline the confirmations rather than round-tripping on each one:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val asyncPublish: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      futureAck <- js.publishAsync(subject"orders.new", "order-123")
      ack       <- futureAck.orDie
      _         <- ZIO.debug(s"Stored at seq: ${ack.seqno}")
    } yield ()
  }
```

## Managing streams

A **stream** captures every message published to a matching set of subjects and retains them according to its configuration. Streams are created server-side and persist until explicitly deleted. Use `JetStreamManagement` to create and configure streams before publishing to them.

The `subjects` list accepts standard NATS wildcards - `*` for a single token, `>` for one-or-more trailing tokens - so a single stream can cover an entire subject hierarchy. `StreamConfig` defaults are production-ready: file storage, `Limits` retention policy, and `Old` discard behaviour (drop the oldest messages when limits are reached). Pass only what you need to override:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val createStream: ZIO[JetStreamManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStreamManagement] { jsm =>
    jsm.addStream(
      StreamConfig(
        name        = "ORDERS",
        subjects    = List("orders.>"),
        maxAge      = Some(7.days),
        storageType = StorageType.File
      )
    ).unit
  }
```

Three retention policies control when messages are removed from a stream:

| Policy | Behaviour |
|--------|-----------|
| `RetentionPolicy.Limits` | Retain messages until size/age/count limits are reached (default) |
| `RetentionPolicy.Interest` | Remove a message once all consumers have acknowledged it |
| `RetentionPolicy.WorkQueue` | Remove a message as soon as any single consumer acknowledges it |

`WorkQueue` retention is the JetStream equivalent of a Core NATS queue group, but with persistence - messages wait in the stream even when all workers are offline and are delivered once a worker reconnects.

## Managing consumers

A **consumer** is a named cursor that tracks an independent read position within a stream. Multiple consumers on the same stream each receive every message - subject to their filter subject configuration. The server persists a durable consumer's position across restarts; if your process crashes mid-batch, it resumes from the last unacknowledged message.

`ConsumerConfig.durable` is a convenience constructor that sets the durable name and sensible defaults. Override individual fields with `.copy` to configure filtering or ack behaviour:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val createConsumer: ZIO[JetStreamManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStreamManagement] { jsm =>
    jsm.addOrUpdateConsumer(
      "ORDERS",
      ConsumerConfig.durable("processor").copy(
        filterSubject = Some("orders.new"),
        ackPolicy     = AckPolicy.Explicit,
        maxDeliver    = 5
      )
    ).unit
  }
```

`AckPolicy.Explicit` requires your code to call `msg.ack` for each message. The server re-delivers an unacknowledged message after `ackWait` (default 30 seconds). `maxDeliver = 5` prevents infinite redelivery - after five attempts the message is abandoned rather than looping forever.

## Consuming

`JetStream#consumer` returns a `Consumer` handle bound to a durable consumer. All delivery methods return a `ZStream[Any, NatsError, JsEnvelope[A]]` (except `next`, which returns an `IO`). `JsEnvelope[A]` bundles `env.value` (the decoded domain type) and `env.message` (the raw `JetStreamMessage` used for ack operations). `A` can be any type with a `NatsCodec` in scope - a plain `String`, `Chunk[Byte]`, or a domain type like `OrderPlaced` with a derived codec (see [Serialization](./02-serialization.md)).

### fetch

`Consumer#fetch` pulls up to `maxMessages` messages and completes the stream once all are delivered or `expiresIn` elapses. This is the batch-processing pattern - use it when you want to work through a fixed window of messages in one pass and then stop:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val batchProcess: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")
      _        <- consumer.fetch[String](FetchOptions(maxMessages = 10, expiresIn = 5.seconds))
                    .mapZIO(env => ZIO.debug(env.value) *> env.ack)
                    .runDrain
    } yield ()
  }
```

### consume

`Consumer#consume` runs indefinitely, with the server pushing messages as they arrive. This is the event-driven pattern - the stream stays open and your handler fires on every new message until the stream is interrupted:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val eventDriven: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")
      _        <- consumer.consume[String]()
                    .mapZIO(env => ZIO.debug(env.value) *> env.ack)
                    .runDrain
    } yield ()
  }
```

### iterate

`Consumer#iterate` is a pull-based polling loop that retries automatically on empty polls. Use it when you want to control the polling rhythm yourself - a scheduled job that wakes up periodically, or a processor that naturally slows down when the queue drains:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val pollingLoop: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")
      _        <- consumer.iterate[String]()
                    .mapZIO(env => ZIO.debug(env.value) *> env.ack)
                    .runDrain
    } yield ()
  }
```

### next

`Consumer#next` retrieves a single message and returns `None` if nothing arrives within the timeout. Use it for sporadic reads - a startup routine that loads initial state from a stream, or a health-check that confirms messages are flowing:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val singleMessage: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")
      maybeMsg <- consumer.next[String](5.seconds)
      _        <- ZIO.foreach(maybeMsg)(env => ZIO.debug(env.value) *> env.ack)
    } yield ()
  }
```

## Ordered consumers

An ordered consumer guarantees strict in-order delivery and automatically re-creates itself after a reconnect or sequence gap. Unlike a durable consumer, it requires no server-side state and no `ack` calls - the server advances the cursor automatically.

Use ordered consumers for read-only workloads: tailing a log stream from the beginning, replaying historical events for an audit report, or watching for configuration changes. Because the server stores no state, an ordered consumer is also cheaper to create and tear down than a durable one. `JetStream#orderedConsumer` accepts an `OrderedConsumerConfig` to filter subjects and set the starting delivery point:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val tailLog: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      ordered <- js.orderedConsumer(
                   "ORDERS",
                   OrderedConsumerConfig(
                     filterSubjects = List("orders.>"),
                     deliverPolicy  = Some(DeliverPolicy.All)
                   )
                 )
      _       <- ordered.consume[String]()
                   .tap(env => ZIO.debug(env.value))
                   .runDrain
    } yield ()
  }
```

`OrderedConsumer` exposes the same `fetch`, `consume`, `iterate`, and `next` methods as `Consumer`. No `ack` calls are needed.

## Acknowledging messages

Acknowledgement tells the JetStream server how to advance a durable consumer's cursor. Unlike Core NATS, which forgets messages immediately, JetStream tracks every unacknowledged message and re-delivers it after the `ackWait` deadline expires.

After receiving a `JsEnvelope`, call one of these proxy methods to signal the outcome to the server. These methods delegate to the underlying `JetStreamMessage` but are more ergonomic to call directly on the envelope:

| Method | Effect |
|--------|--------|
| `env.ack` | Message processed - advance the cursor |
| `env.ackSync(timeout)` | Ack and wait for server confirmation |
| `env.nak` | Processing failed - redeliver immediately |
| `env.nakWithDelay(d)` | Processing failed - redeliver after delay `d` |
| `env.term` | Permanently reject - do not redeliver |
| `env.inProgress` | Still working - extend the ack deadline |

`env.inProgress` is valuable for long-running handlers. Call it periodically to prevent the server from assuming the message was lost and triggering a redelivery while your handler is still running.

### Auto-acknowledgement

For simple worker patterns where you want to acknowledge on success and negatively-acknowledge on any failure, use the `runProcessAck` extension method. It runs the provided handler for every message and handles the acknowledgement boilerplate for you:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val autoAck: ZIO[JetStream & Scope, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")
      _        <- consumer.consume[String]()
                    .runProcessAck(id => Console.printLine(s"Processed $id").orDie)
    } yield ()
  }
```

If the handler (the function passed to `runProcessAck`) fails, `env.nak` is called automatically and the error is swallowed (but logged by the underlying NATS client).

## Next steps

- [Service Framework guide](./04-service.md) - build discoverable microservices with typed request-reply endpoints on top of NATS
- [Key-Value guide](./05-key-value.md) - KV is built on JetStream streams and adds a higher-level API for point-in-time lookups and change watches
- [Configuration guide](./08-configuration.md) - connecting to authenticated or TLS-secured NATS servers with JetStream enabled
