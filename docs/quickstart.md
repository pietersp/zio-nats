---
id: quickstart
title: Quick start
---

# Quick start

## Prerequisites

A running NATS server. The quickest way:

```bash
docker run -p 4222:4222 nats
```

Or [download nats-server](https://docs.nats.io/running-a-nats-service/introduction/installation) directly.

## Installation

### Batteries-included

Includes pub/sub, JetStream, Key-Value, Object Store, the Service Framework,
and zio-blocks type-safe serialization:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"
```

### Core only

Use this when you prefer jsoniter-scala or a fully custom `NatsCodec[A]`
instead of zio-blocks:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-core" % "@VERSION@"
```

### Optional serialization add-ons

```scala
// jsoniter-scala — pair with core, or alongside zio-nats for specific types
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter"  % "@VERSION@"

// play-json — same pairing options as jsoniter
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "@VERSION@"

// Test helpers — starts a NATS container via testcontainers
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit"   % "@VERSION@" % Test
```

## First program

Publish three messages and receive them on a subscription:

```scala
import zio._
import zio.nats._
import zio.nats.config.NatsConfig

object Main extends ZIOAppDefault {

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats  <- ZIO.service[Nats]
      fiber <- nats.subscribe[String](Subject("greetings"))
                 .take(3)
                 .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                 .runDrain
                 .fork
      _     <- ZIO.sleep(100.millis)
      _     <- ZIO.foreachDiscard(1 to 3)(i =>
                 nats.publish(Subject("greetings"), s"Hello #$i")
               )
      _     <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(NatsConfig.live, Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
```

A few things to note:

- `NatsConfig.live` connects to `nats://localhost:4222` with no auth and no TLS — the default for local development.
- `nats.subscribe[String]` decodes each payload as UTF-8 and wraps it in an `Envelope[String]`. Use `Chunk[Byte]` for raw bytes.
- `Subject` is an opaque type alias for `String` (zero runtime overhead) — construct with `Subject("my.topic")`.
- The `Nats.live` ZLayer drains all subscriptions and closes the connection automatically when the scope ends.

## Loading config from the environment

Swap `NatsConfig.live` for `NatsConfig.fromConfig` to read settings from environment variables:

```scala
app.provide(
  Nats.live,
  NatsConfig.fromConfig   // reads NATS_* env vars
)
```

Key environment variables:

| Variable | Example | Default |
|----------|---------|---------|
| `NATS_SERVERS` | `nats://broker:4222` | `nats://localhost:4222` |
| `NATS_AUTH_TYPE` | `token` | `no-auth` |
| `NATS_AUTH_VALUE` | `s3cr3t` | — |
| `NATS_CONNECTION_TIMEOUT` | `PT5S` | `PT2S` |
| `NATS_TLS_TYPE` | `system-default` | `disabled` |

All duration values use ISO-8601 format (`PT5S` = 5 s, `PT2M` = 2 min, `PT0.5S` = 500 ms).
