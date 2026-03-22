---
id: jetstream
title: JetStream
---

# JetStream

> Persistent, replay-able messaging with guaranteed delivery.

**JetStream** is the persistence layer of NATS. Unlike core pub/sub, messages are stored in
**streams** on the server and replayed to **consumers** — named cursors that remember their
position. This gives you at-least-once delivery, message replay, and backpressure.

## Prerequisites

- [Quick start](../quickstart.md) completed
- JetStream-enabled NATS server:

```bash
docker run --rm -p 4222:4222 nats -js
```

## Publishing

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val publishExample: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      ack <- js.publish(Subject("orders.new"), "order-payload")
      _   <- ZIO.debug(s"Stored at sequence: ${ack.seqno}")
    } yield ()
  }

val publishLayer =
  ZLayer.succeed(NatsConfig.default) >>> Nats.live >>> JetStream.live
```

**What's happening:**

1. `js.publish(...)` — sends a message to the server and waits for a publish acknowledgement (`PublishAck`). The ack contains the stream name and sequence number the server assigned.
2. Unlike core `nats.publish`, this is not fire-and-forget. If the server rejects the message (e.g. the stream does not exist), the effect fails with `NatsError.JetStreamPublishFailed`.

### Publishing with options

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

// Duplicate detection via message ID — the server deduplicates within its dedup window
val dedupPublish: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    js.publish(
      Subject("orders.new"),
      "order-payload",
      JsPublishParams(options = Some(PublishOptions(messageId = Some("order-42"))))
    ).unit
  }

// Publish with headers
val headerPublish: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    js.publish(
      Subject("orders.new"),
      "order-payload",
      JsPublishParams(headers = Headers("X-Source" -> "checkout"))
    ).unit
  }

// Async publish — fire and collect the ack later
val asyncPublish: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      futureAck <- js.publishAsync(Subject("orders.new"), "order-payload")
      ack       <- futureAck.orDie  // resolves when the server confirms
      _         <- ZIO.debug(s"Async ack seq: ${ack.seqno}")
    } yield ()
  }
```

## Managing streams and consumers

Use `JetStreamManagement` to create streams and consumers before publishing or consuming.

A **stream** is a named, server-side log that captures messages matching a set of subjects.
A **consumer** is a named cursor into a stream — it remembers its position and delivers
messages to your application.

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val setupExample: ZIO[JetStreamManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStreamManagement] { jsm =>
    for {
      _ <- jsm.addStream(
             StreamConfig(
               name        = "ORDERS",
               subjects    = List("orders.>"),
               storageType = StorageType.Memory
             )
           )
      _ <- jsm.addOrUpdateConsumer(
             "ORDERS",
             ConsumerConfig.durable("processor").copy(
               filterSubject = Some("orders.>"),
               ackPolicy     = AckPolicy.Explicit
             )
           )
    } yield ()
  }

val managementLayer =
  ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> JetStreamManagement.live
```

**What's happening:**

1. `StreamConfig(name = "ORDERS", subjects = List("orders.>"), ...)` — every message published to a subject matching `orders.>` is stored in this stream.
2. `ConsumerConfig.durable("processor")` — creates a named (durable) consumer. Durable consumers survive client restarts; the server keeps their position.
3. `ackPolicy = AckPolicy.Explicit` — the consumer does not advance until your code calls `msg.ack`. This prevents message loss if your process crashes mid-processing.

## Consuming — durable consumer

Get a `Consumer` handle from `JetStream.consumer`, then use one of its delivery methods:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val consumeExample: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      consumer <- js.consumer("ORDERS", "processor")

      // Bounded fetch — pull up to N messages, complete when fetched or expired
      _ <- consumer.fetch[String](FetchOptions(maxMessages = 10, expiresIn = 5.seconds))
             .mapZIO(env => ZIO.debug(env.value) *> env.message.ack)
             .runDrain

      // Indefinite push-style consume — server pushes as messages arrive
      _ <- consumer.consume[String]()
             .mapZIO(env => ZIO.debug(env.value) *> env.message.ack)
             .runDrain

      // Pull-based iterate — retries on empty polls
      _ <- consumer.iterate[String]()
             .mapZIO(env => ZIO.debug(env.value) *> env.message.ack)
             .runDrain

      // Single next message — None if nothing arrives within the timeout
      maybeMsg <- consumer.next[String](5.seconds)
      _        <- ZIO.foreach(maybeMsg)(env => ZIO.debug(env.value) *> env.message.ack)
    } yield ()
  }
```

**What's happening:**

1. `js.consumer("ORDERS", "processor")` — binds to the durable consumer you created in management. This is a lightweight lookup; no network round-trip stores state.
2. Each method returns `ZStream[Any, NatsError, JsEnvelope[String]]` (except `next`). `JsEnvelope` bundles `env.value` (the decoded domain type) and `env.message` (the raw `JetStreamMessage` for ack operations).
3. `env.message.ack` — advances the consumer's position. Without it, the server re-delivers the message after the ack deadline expires.
4. `fetch` is best for batch processing; `consume` for event-driven workloads; `iterate` for polling loops.

## Consuming — ordered consumer

An ordered consumer guarantees strict in-order delivery and automatically re-creates itself
on reconnect or sequence gaps. Unlike a durable consumer, it requires no server-side state.
Use it for reading a stream from the beginning, tailing a log, or any read-only workload.

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.jetstream.*

val orderedExample: ZIO[JetStream, NatsError, Unit] =
  ZIO.serviceWithZIO[JetStream] { js =>
    for {
      ordered <- js.orderedConsumer(
                   "ORDERS",
                   OrderedConsumerConfig(
                     filterSubjects = List("orders.>"),
                     deliverPolicy  = Some(DeliverPolicy.All)
                   )
                 )
      _ <- ordered.consume[String]()
             .tap(env => ZIO.debug(env.value))
             .runDrain
    } yield ()
  }
```

`OrderedConsumer` exposes the same `fetch`, `consume`, `iterate`, and `next` methods as `Consumer`.
No `ack` calls are needed — ordered consumers advance automatically.

## Ack methods

After receiving a `JetStreamMessage`, use these methods to signal the outcome to the server:

| Method | Effect |
|---|---|
| `msg.ack` | Acknowledge — message processed successfully, advance the cursor |
| `msg.ackSync(timeout)` | Ack and wait for server confirmation |
| `msg.nak` | Request redelivery immediately |
| `msg.nakWithDelay(d)` | Request redelivery after delay `d` |
| `msg.term` | Terminate — do not redeliver, mark as failed |
| `msg.inProgress` | Extend the ack deadline (work-in-progress signal) |

`msg.decode[A]` decodes the raw payload using the given `NatsCodec[A]` — useful when you have
a `Chunk[Byte]` envelope and want to decode lazily.

## Next steps

- [Key-Value guide](./04-key-value.md) — KV store is built on JetStream streams
- [Serialization guide](./02-serialization.md) — publish and consume typed domain types
- [JetStreamManagement API](../reference/01-nats-config.md) — stream and consumer config fields
