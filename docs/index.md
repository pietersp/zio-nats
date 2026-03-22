---
id: index
title: zio-nats
slug: /
---

# zio-nats

A purely functional NATS client for ZIO 2.

- Idiomatic ZIO 2 services — every subsystem is a `ZLayer`
- `ZStream`-based subscriptions — no callbacks in user code
- Type-safe serialization with built-in `String`/`Chunk[Byte]` codecs; optional zio-blocks, jsoniter-scala, and play-json integrations
- Typed error model — `NatsError` sealed ADT, exhaustively matchable
- Zero raw jnats types — `import zio.nats.*` is the only import you need

```scala mdoc:silent
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

**What's happening:**

1. `ZIO.service[Nats]` — acquires the `Nats` connection from the ZIO environment.
2. `nats.subscribe[String](Subject("greetings"))` — opens a `ZStream` of incoming messages. Each payload is decoded from UTF-8 via the built-in `NatsCodec[String]` and wrapped in an `Envelope[String]`. Access the decoded value via `env.value`.
3. `.take(3).fork` — the stream completes after 3 messages and runs in the background so publishing can proceed.
4. `nats.publish(...)` — encodes the string to UTF-8 and sends it. No codec setup required for `String` or `Chunk[Byte]`.

---

[Quick start →](./quickstart) · [Browse guides →](./guides/01-pubsub)
