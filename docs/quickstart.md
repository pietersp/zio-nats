---
id: quickstart
title: Quick start
sidebar_position: 1
---

## Prerequisites

To follow along you need an sbt project with Scala 3 and a running NATS server. The easiest way to start one locally is:

```bash
docker run --rm -p 4222:4222 nats
```

Or download `nats-server` directly from [nats.io/download](https://nats.io/download).

## Installation

Add the batteries-included artifact to `build.sbt`:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"
```

This includes Core NATS pub/sub, JetStream, Key-Value, Object Store, and zio-blocks serialization. See [Modules](./reference/03-modules.md) for fine-grained artifact options.

## Connect

Wire the `Nats` service into your app using `NatsConfig.live` and `Nats.live`. `NatsConfig.live` reads connection settings from the environment and defaults to `nats://localhost:4222`; `Nats.live` opens the TCP connection and closes it automatically when the scope exits:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val connect: ZIO[Any, Throwable, Unit] =
  ZIO.unit.provide(NatsConfig.live, Nats.live)
```

For local development, `Nats.default` is a shorthand that combines both layers:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val connectShort: ZIO[Any, Throwable, Unit] =
  ZIO.unit.provide(Nats.default)
```

## Publish and subscribe with domain types

Rather than passing raw strings, zio-nats lets you publish and subscribe using your own domain types. We are going to define a `User` case class, derive a schema with zio-blocks, and let the library handle all JSON serialization.

There are two setup steps:

1. Derive a `Schema[User]` - a compile-time description of the type's structure that zio-blocks uses to generate serialization logic.
2. Build a codec builder from a format with `NatsCodec.fromFormat(JsonFormat)`, then `import codecs.derived` to bring `NatsCodec[User]` into implicit scope.

Here is a complete example showing the full setup and a publish-subscribe round-trip:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class User(name: String, age: Int)
object User {
  given Schema[User] = Schema.derived
}

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived  // NatsCodec[User] is now in implicit scope

object Main extends ZIOAppDefault {

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats    <- ZIO.service[Nats]
      subject  = Subject("users")
      fiber   <- nats.subscribe[User](subject)
                   .take(2)
                   .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                   .runDrain
                   .fork
      _       <- ZIO.sleep(200.millis)
      _       <- nats.publish(subject, User("Alice", 30))
      _       <- nats.publish(subject, User("Bob", 25))
      _       <- fiber.join
    } yield ()

  val run =
    program.provide(NatsConfig.live, Nats.live).orDie
}
```

Run it with `sbt run`. You should see:

```
Received: User(Alice,30)
Received: User(Bob,25)
```

:::tip
`import codecs.derived` is where zio-blocks compiles and caches the serializer for `User`. If a `Schema` is missing or the format cannot handle the type, you get an error at the import - not buried in a later `publish` call.
:::

## What just happened

That was a lot in a few lines. Here are the things worth noticing.

The publisher and subscriber share only a subject name - `"users"`. Neither knows about the other. This is Core NATS: a lightweight, broker-mediated message bus where any number of producers and consumers can come and go independently. Swapping the subject or adding a second subscriber requires no changes to the publisher.

Serialization was entirely automatic. We published a `User` and received a `User` - no manual JSON encoding, no `decode` calls, no casting. The `Schema[User]` told zio-blocks the shape of the type; `NatsCodec.fromFormat(JsonFormat)` turned that into a serializer; and `import codecs.derived` made it available wherever the compiler resolves a `NatsCodec[User]`. If the schema were missing, the import would fail - not the first `publish` call at runtime.

The subscription is a plain `ZStream`. `.take(2)`, `.tap`, `.map`, `.filter` - all the standard ZIO stream operators work directly on incoming messages. There are no listeners to register, no callbacks to manage, and no thread safety concerns to think about.

## Next steps

- [Serialization guide](./guides/02-serialization.md) - jsoniter-scala, play-json, custom codecs, and per-type overrides
- [Pub/Sub guide](./guides/01-pubsub.md) - queue groups, request-reply, headers
- [JetStream guide](./guides/03-jetstream.md) - durable streams, at-least-once and exactly-once delivery
