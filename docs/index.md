---
id: index
title: zio-nats
slug: /
---

## Overview

[NATS](https://nats.io) is a lightweight, high-performance messaging system. It organises messages around **subjects** - dot-separated strings like `orders.created`, or `payments.US.west.store1` - and lets any number of producers and consumers exchange messages without knowing about each other. zio-nats brings this to ZIO 2 with a clean, purely functional API.

- **Pub/sub with Core NATS** - producers and consumers are fully decoupled; neither knows about the other. Core NATS is at-most-once delivery: fast and lightweight, but if no subscriber is listening when a message arrives, it is gone. Use queue groups to spread load across multiple instances of a service.
- **JetStream** - when you can't afford to lose messages, JetStream persists them to durable streams. Consumers can catch up after downtime, replay from any point, and process at their own pace. At-least-once delivery is the default; exactly-once delivery is also supported for the cases where duplicates are not acceptable.
- **Key-Value** - a reactive store for shared state and configuration, backed by JetStream. Watch any key for changes as a stream, making it a natural fit for feature flags, distributed config, or coordinating state across services.
- **Object Store** - distribute large binary objects across your NATS cluster without standing up S3 or a separate file store. A natural fit for ML models, compiled assets, or config bundles that need to be available to all your services.
- **Service framework** - NATS has a built-in microservice protocol (Micro) that gives you request-reply endpoints with automatic service discovery, health checks, and stats; no service mesh, no sidecar, no extra infrastructure
- **Type-safe serialization** - batteries-included zio-blocks integration for domain types, with jsoniter-scala and play-json also supported; easy to bring your own
- **Ergonomic by design** - no raw jnats types, no callbacks, no adapters, simple imports; the API feels like it was written for ZIO from the ground up


## A taste of the API

Here is a complete publish-and-subscribe example over a NATS subject:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val program: ZIO[Nats, NatsError, Unit] =
  for {
    nats  <- ZIO.service[Nats]
    fiber <- nats.subscribe[String](Subject("greetings"))
               .take(3)
               .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
               .runDrain
               .fork
    _     <- ZIO.sleep(200.millis)
    _     <- ZIO.foreachDiscard(1 to 3) { i =>
               nats.publish(Subject("greetings"), s"Hello #$i")
             }
    _     <- fiber.join
  } yield ()
```

What's happening on the NATS side:

1. `Subject("greetings")` - every NATS message is published to a subject. Subscribers match subjects exactly or via wildcards (`greetings.*`, `greetings.>`).
2. `Nats#subscribe[String]` - opens a live subscription as a `ZStream`. Each arriving payload is decoded via `NatsCodec[String]` and wrapped in an `Envelope`; the raw subject, headers, and reply-to are also available on `env.message`.
3. `Nats#publish` - sends a message to the subject. Any active subscriber receives it; if there are none, the message is dropped (fire-and-forget semantics). For durability, use JetStream instead.

:::tip
Need guaranteed delivery, replay, or persistence? The [JetStream guide](./guides/03-jetstream.md) covers durable streams and consumers. For a key-value store or large-object storage, see the [Key-Value](./guides/05-key-value.md) and [Object Store](./guides/06-object-store.md) guides.
:::

---

[Quick start →](./quickstart.md) · [Browse guides →](./guides/01-pubsub.md)
