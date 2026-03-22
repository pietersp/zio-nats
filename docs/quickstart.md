---
id: quickstart
title: Quick start
sidebar_position: 1
---

# Quick start

Get from zero to your first published and received message.

## Prerequisites

- sbt project with Scala 3
- A running NATS server:

```bash
docker run --rm -p 4222:4222 nats
```

Or download `nats-server` from [nats.io/download](https://nats.io/download) and run it directly.

## Installation

Add to `build.sbt`:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"
```

This is the batteries-included artifact. It includes pub/sub, JetStream, Key-Value, Object Store,
and zio-blocks type-safe serialization. See [Modules](./reference/03-modules.md) for the full list
of available artifacts.

## Connect

Wire the `Nats` service into your app using `NatsConfig.live` and `Nats.live`:

```scala mdoc:silent
import zio.*
import zio.nats.*

val connect: ZIO[Any, Throwable, Unit] =
  ZIO.unit.provide(NatsConfig.live, Nats.live)
```

**What's happening:**

1. `NatsConfig.live` — builds a `NatsConfig` layer using defaults: `nats://localhost:4222`, no auth, no TLS.
2. `Nats.live` — opens the NATS TCP connection and exposes the `Nats` service. The connection is closed automatically when the scope exits.
3. `.provide(...)` — wires the two layers together. `Nats.live` consumes `NatsConfig`; your program consumes `Nats`.

For local development you can use `Nats.default`, which is a `ZLayer` equivalent to the two layers combined:

```scala mdoc:silent
val connectShort: ZIO[Any, Throwable, Unit] =
  ZIO.unit.provide(Nats.default)
```

## Publish and subscribe

```scala mdoc:silent
import zio.*
import zio.nats.*

object Main extends ZIOAppDefault {

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats  <- ZIO.service[Nats]

      // Start listening before publishing so no messages are missed
      fiber <- nats.subscribe[String](Subject("greetings"))
                 .take(3)
                 .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                 .runDrain
                 .fork

      _     <- ZIO.sleep(200.millis)

      // Publish three messages
      _     <- ZIO.foreachDiscard(1 to 3) { i =>
                 nats.publish(Subject("greetings"), s"Hello #$i")
               }

      _     <- fiber.join
    } yield ()

  val run =
    program
      .provide(NatsConfig.live, Nats.live)
      .orDie
}
```

**What's happening:**

1. `nats.subscribe[String](Subject("greetings"))` — opens a `ZStream[Nats, NatsError, Envelope[String]]`. The type parameter `String` selects the built-in UTF-8 codec automatically.
2. `.take(3)` — the stream completes normally after 3 messages, which closes the underlying NATS subscription.
3. `.tap(env => ...)` — for each message, prints `env.value` (the decoded `String`). `env.message` is also available for headers, subject, and reply-to.
4. `.fork` — runs the stream in a background fiber so we can publish below.
5. `ZIO.sleep(200.millis)` — brief pause to ensure the subscription is active before the first publish.
6. `nats.publish(Subject("greetings"), s"Hello #$i")` — encodes the string to UTF-8 and sends it. No codec setup required.
7. `fiber.join` — waits for the subscriber fiber to receive all 3 messages and complete.

Run it with `sbt run`. You should see:

```
Received: Hello #1
Received: Hello #2
Received: Hello #3
```

## Next steps

- [Pub/Sub guide](./guides/01-pubsub.md) — queue groups, request-reply, headers, raw bytes
- [Serialization guide](./guides/02-serialization.md) — type-safe publish/subscribe with domain types
- [Architecture](./concepts/01-architecture.md) — understand the full `ZLayer` service graph
