---
id: pubsub
title: Pub/Sub
---

# Pub/Sub

> Publish messages, subscribe to subjects, use queue groups, and send request-reply RPCs.

## Prerequisites

- [Quick start](../quickstart.md) completed — you have a working `Nats` layer

## Publish

```scala mdoc:silent
import zio.*
import zio.nats.*

val publishExamples: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      // UTF-8 string — built-in NatsCodec[String]
      _ <- nats.publish(Subject("events.user"), "payload")

      // Raw bytes — built-in NatsCodec[Chunk[Byte]]
      _ <- nats.publish(Subject("events.user"), Chunk.fromArray("raw".getBytes))

      // With headers
      _ <- nats.publish(
             Subject("events.user"),
             "payload",
             PublishParams(headers = Headers("Content-Type" -> "application/json"))
           )

      // With reply-to
      _ <- nats.publish(
             Subject("events.user"),
             "payload",
             PublishParams(replyTo = Some(Subject("_INBOX.reply")))
           )
    } yield ()
  }
```

**What's happening:**

1. `Subject("events.user")` — `Subject` is an opaque type alias for `String`. It compiles to a plain `String` at runtime with no overhead.
2. The second argument's type selects the codec automatically. `String` → UTF-8 encode. `Chunk[Byte]` → identity (no conversion).
3. `PublishParams` is optional. It carries headers and reply-to. Omit it when you don't need either.

## Headers

`Headers` is an immutable multi-value map. Build it, read it, and merge it:

```scala mdoc:silent
import zio.nats.*

val h = Headers("X-Trace" -> "abc", "Content-Type" -> "application/json")

val traceValues: Chunk[String] = h.get("X-Trace")           // Chunk("abc")
val withExtra: Headers         = h.add("X-Trace", "def")    // Chunk("abc", "def") for X-Trace
```

## Subscribe

`subscribe[A]` returns a `ZStream` of `Envelope[A]`. The underlying NATS subscription opens
when the stream is consumed and closes when it is interrupted or completed.

```scala mdoc:silent
import zio.*
import zio.nats.*

val subscribeExamples: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      // Typed — payload decoded as UTF-8
      _ <- nats.subscribe[String](Subject("events.>"))
             .tap(env => ZIO.debug(s"${env.message.subject}: ${env.value}"))
             .runDrain

      // Raw bytes — full NatsMessage available via env.message
      _ <- nats.subscribe[Chunk[Byte]](Subject("events.>"))
             .tap(env => ZIO.debug(s"${env.message.subject}: ${env.message.dataAsString}"))
             .runDrain
    } yield ()
  }
```

**What's happening:**

1. `subscribe[String]` — the type parameter selects `NatsCodec[String]` (built-in, UTF-8). The decoded value is `env.value`.
2. `subscribe[Chunk[Byte]]` — selects the identity codec. Useful when you need the raw bytes or when another layer will decode them.
3. `env.message` is the raw `NatsMessage`. It carries `.subject`, `.headers`, `.replyTo`, and `.dataAsString`. It is available on both typed and raw subscriptions.
4. NATS subjects support wildcards: `*` matches one token, `>` matches one or more tokens.
5. `runDrain` runs the stream until interrupted. In practice, fork it alongside your application logic.

## Queue groups

A queue group distributes messages across all subscribers sharing the same group name. Each
message is delivered to exactly one member of the group — useful for worker pools.

```scala mdoc:silent
import zio.*
import zio.nats.*

val queueGroupExample: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.subscribe[String](Subject("work.queue"), Some(QueueGroup("workers")))
      .tap(env => ZIO.debug(s"Worker processing: ${env.value}"))
      .runDrain
  }
```

`QueueGroup` is an opaque type alias for `String`. Omit it (pass `None` or no second argument)
for a regular fan-out subscription where every subscriber receives every message.

## Request-Reply

`request` sends a message and waits for a single reply. It is the NATS pattern for synchronous
RPC over an async transport.

```scala mdoc:silent
import zio.*
import zio.nats.*

val rpcExample: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      // Returns Envelope[String] — decoded reply + raw metadata
      env <- nats.request[String, String](
               Subject("rpc.greet"),
               "Alice",
               timeout = 5.seconds
             )
      _ <- ZIO.debug(s"Reply: ${env.value}")

      // .payload strips the Envelope wrapper — returns just the decoded value
      reply <- nats.request[String, String](Subject("rpc.greet"), "Bob", 5.seconds).payload
      _     <- ZIO.debug(s"Reply: $reply")
    } yield ()
  }
```

**What's happening:**

1. The two type parameters are `[In, Out]`: the request payload type and the expected reply type.
2. zio-nats creates an ephemeral `_INBOX` subject for the reply automatically.
3. `timeout` is required — `request` fails with `NatsError.Timeout` if no reply arrives in time.
4. `.payload` is an extension method on `IO[NatsError, Envelope[A]]` that strips the `Envelope` wrapper, returning `IO[NatsError, A]`.

## Next steps

- [Serialization guide](./02-serialization.md) — publish and subscribe with domain types (not just `String`/`Chunk[Byte]`)
- [JetStream guide](./03-jetstream.md) — persistent, replay-able messaging with delivery guarantees
- [Configuration guide](./06-configuration.md) — connecting to authenticated or TLS-secured servers
