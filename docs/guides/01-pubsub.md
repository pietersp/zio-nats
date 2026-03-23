---
id: pubsub
title: Pub/Sub
---

## Publishing

`Nats#publish` fires a message to a subject and returns immediately. Every active subscriber on that subject receives a copy; the publisher neither knows nor cares who is listening. This makes it a natural fit for event broadcasting - when an order is placed, a single publish to `shop.orders` lets a warehouse service, a payment processor, and a notification service each react independently without any of them being coupled to each other or to the publisher.

Messages are delivered at-most-once. If no subscriber is listening when a message arrives, it is dropped silently.

### Fire-and-forget

The simplest publish takes a subject and a payload. The type of the payload selects the `NatsCodec` automatically:

```scala mdoc:silent
import zio.*
import zio.nats.*

val publishBasic: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("shop.orders"), "order-123")
      _ <- nats.publish(Subject("shop.orders"), Chunk.fromArray("order-123".getBytes))
    } yield ()
  }
```

`"order-123"` resolves `NatsCodec[String]` (UTF-8 encode). `Chunk.fromArray(...)` resolves `NatsCodec[Chunk[Byte]]` (identity - no conversion). Both are built-in; no setup required.

### With headers

Headers travel with every message and are useful for metadata that consumers need without deserializing the payload - a trace ID for distributed tracing, a source tag for routing, or a schema version for compatibility checks.

`Headers` is an immutable multi-value map. We build one first:

```scala mdoc:silent
import zio.nats.*

val traceHeaders = Headers("X-Trace-Id" -> "req-abc123", "X-Source" -> "checkout")
```

`.get` returns all values for a key as a `Chunk[String]`. `.add` appends to existing values rather than replacing them:

```scala mdoc
traceHeaders.get("X-Trace-Id")
traceHeaders.add("X-Trace-Id", "req-def456").get("X-Trace-Id")
```

To publish with headers, pass a `PublishParams`:

```scala mdoc:silent
import zio.*
import zio.nats.*

val publishWithHeaders: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.publish(
      Subject("shop.orders"),
      "order-123",
      PublishParams(headers = traceHeaders)
    )
  }
```

### With reply-to

A reply-to subject tells the receiver where to send a response. In NATS, these are typically `_INBOX` subjects - short-lived ephemeral subjects that a single client listens on. `Nats#request` creates and manages an inbox automatically for simple request-reply (see [Request-Reply](#request-reply)). You only need to set a reply-to manually when building a custom pattern, such as a scatter-gather that fans out to multiple services and collects all their replies:

```scala mdoc:silent
import zio.*
import zio.nats.*

val publishWithReplyTo: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.publish(
      Subject("shop.pricing"),
      "item-456",
      PublishParams(replyTo = Some(Subject("_INBOX.my-gather-123")))
    )
  }
```

## Subscribing

A subscriber receives messages as a `ZStream[Nats, NatsError, Envelope[A]]`. Each `Envelope[A]` carries two things:

- `env.value` - the decoded payload, typed as `A`
- `env.message` - the raw NATS message, with `.subject`, `.headers`, `.replyTo`, and `.dataAsString`

Most of the time `env.value` is all you need. Reach for `env.message` when you need to inspect headers, read the subject the message arrived on, or send a reply.

The NATS subscription opens when the stream starts consuming and closes when the stream is interrupted or completes. NATS subjects support two wildcards:

- `*` matches a single token: `shop.orders.*` matches `shop.orders.new` but not `shop.orders.new.express`
- `>` matches one or more trailing tokens: `shop.orders.>` matches `shop.orders.new`, `shop.orders.new.express`, and anything deeper

### Typed payload

Pass the expected type as a type parameter and `NatsCodec[A]` is resolved at compile time - the payload is decoded automatically on every incoming message. `A` can be any type with a `NatsCodec` in scope: a plain `String`, a `Chunk[Byte]`, or a domain type like `OrderPlaced` with a derived codec (see [Serialization](./02-serialization.md)). This is the right choice whenever your service cares about the content: a notification service subscribing to `shop.orders.>` to send confirmation emails only needs the decoded order ID, not raw bytes or message metadata:

```scala mdoc:silent
import zio.*
import zio.nats.*

val subscribeWithEnvelope: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.subscribe[String](Subject("shop.orders.>"))
      .tap(env => ZIO.debug(s"Order on ${env.message.subject}: ${env.value}"))
      .runDrain
  }
```

If you only need the decoded values and have no use for the envelope metadata, use `.map(_.value)` to unwrap the stream:

```scala mdoc:silent
import zio.*
import zio.nats.*

val subscribeValues: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.subscribe[String](Subject("shop.orders.>"))
      .map(_.value)
      .tap(orderId => ZIO.debug(s"Processing: $orderId"))
      .runDrain
  }
```

### Raw bytes

Subscribe as `Chunk[Byte]` when you want to handle decoding yourself or pass messages through without touching the payload. This is useful for proxies, log archivers, or bridges to third-party systems - a message bridge forwarding order events to an analytics platform does not need to decode them, it just passes the bytes through:

```scala mdoc:silent
import zio.*
import zio.nats.*

val subscribeRaw: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.subscribe[Chunk[Byte]](Subject("shop.orders.>"))
      .tap(env => ZIO.debug(s"Forwarding ${env.value.length} bytes from ${env.message.subject}"))
      .runDrain
  }
```

## Queue groups

A queue group distributes messages across all subscribers sharing the same group name, delivering each message to exactly one member. This is the Core NATS pattern for running multiple instances of a worker service without each instance processing every message. Deploy three fulfillment workers all subscribed to `shop.fulfillment` with the same group name and NATS spreads orders evenly across them - no coordination logic required.

Pass a `QueueGroup` as the second argument to `Nats#subscribe`:

```scala mdoc:silent
import zio.*
import zio.nats.*

val fulfillmentWorker: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.subscribe[String](Subject("shop.fulfillment"), Some(QueueGroup("fulfillment-workers")))
      .tap(env => ZIO.debug(s"Fulfilling order: ${env.value}"))
      .runDrain
  }
```

Start as many instances as you need with the same `QueueGroup` name and NATS distributes messages across them automatically. Scale up by adding instances; scale down by stopping them. No re-configuration, no locks, no extra infrastructure.

## Request-Reply

Request-reply is Core NATS's pattern for synchronous RPC over an async transport. `Nats#request` publishes a message and waits for a single reply, making it feel like a function call while staying entirely within NATS - no HTTP servers, no extra routing infrastructure. It is a natural fit for lookups and validations: a checkout service confirming stock with the inventory service, or a pricing service returning a quote on demand. `Nats#request` handles the entire exchange - it creates an ephemeral `_INBOX` subject, subscribes to it, attaches it as the reply-to on the outgoing message, waits for the response, then cleans up.

### Basic request

The two type parameters are `[In, Out]`: the request payload type and the expected reply type. To show the typed nature, we define a `StockStatus` reply type. `available` is the number of units ready to ship; `onHold` is the number reserved by pending orders. We use zio-blocks to derive a codec (see [Serialization](./02-serialization.md) for details):

```scala mdoc:silent
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockStatus(available: Int, onHold: Int)
object StockStatus {
  given Schema[StockStatus] = Schema.derived
}

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived
```

A `timeout` is required - if no reply arrives in time, `Nats#request` fails with `NatsError.Timeout`:

```scala mdoc:silent
import zio.*
import zio.nats.*

val stockCheck: ZIO[Nats, NatsError, Envelope[StockStatus]] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.request[String, StockStatus](
      Subject("shop.inventory"),
      "item-456",
      timeout = 5.seconds
    )
  }
```

### Unwrapping the reply

`Nats#request` returns an `Envelope[Out]`, giving access to both the decoded reply and the raw metadata. Similarly to how `.map(_.value)` unwraps a subscription stream to a `ZStream` of plain values, `.payload` unwraps a single `IO[NatsError, Envelope[A]]` to give you just the decoded value:

```scala mdoc:silent
import zio.*
import zio.nats.*

val stockLevel: ZIO[Nats, NatsError, StockStatus] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.request[String, StockStatus](
          Subject("shop.inventory"),
          "item-456",
          5.seconds
        ).payload
  }
```

## Next steps

- [Serialization guide](./02-serialization.md) - publish and subscribe with domain types, not just `String` and `Chunk[Byte]`
- [JetStream guide](./03-jetstream.md) - durable streams, at-least-once and exactly-once delivery
- [Configuration guide](./07-configuration.md) - connecting to authenticated or TLS-secured servers
