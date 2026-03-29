---
id: lifetimes
title: Resource Lifetimes
---

Every zio-nats resource - connections, subscriptions, consumers, watch streams, services - has a well-defined lifetime tied to a ZIO `Scope`. When the scope closes, the resource is released automatically, whether the scope ended normally, with an error, or due to fiber interruption. This page explains where each resource lives and what cleanup happens when it is released.

## The `Nats` connection

The `Nats` layer acquires a TCP connection on startup and holds it for the lifetime of the layer's scope. When the scope closes - at application shutdown or when the test suite finishes - jnats calls `drain` before closing. Drain waits for all active subscriptions to unsubscribe and all in-flight messages to be acknowledged by the server. If drain takes longer than `drainTimeout` (default 30 seconds, configurable in `NatsConfig`), the connection is closed anyway:

```
ZLayer scope opens  →  connection established
ZLayer scope closes →  drain all subscriptions →  flush in-flight messages →  close TCP connection
```

For publish-heavy programs, call `nats.flush(timeout)` before a planned shutdown to guarantee that all buffered outgoing messages have left the client before the drain begins:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val gracefulShutdown: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats](_.flush(2.seconds))
```

## Subscriptions

`Nats#subscribe` returns a `ZStream`. The NATS subscription exists for exactly as long as the stream is running - when the stream completes or is interrupted, the dispatcher is closed and the server stops delivering messages to it. No extra cleanup step is needed.

This means subscription lifetime follows the same rules as any ZIO resource: interrupt the fiber running the stream to cancel the subscription:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val subscribeAndCancel: ZIO[Nats, NatsError, Unit] =
  for {
    nats  <- ZIO.service[Nats]
    fiber <- nats.subscribe[String](subject"events.>")
                 .tap(env => ZIO.debug(env.value))
                 .runDrain
                 .fork
    _     <- ZIO.sleep(5.seconds)
    _     <- fiber.interrupt  // unsubscribes from events.>
  } yield ()
```

A stream that completes naturally - for example via `.take(n)` - also unsubscribes cleanly. There is no difference in cleanup behavior between interruption and natural completion.

## JetStream consumers

Consumer streams follow the same pattern, but the two delivery modes have different completion characteristics:

**`Consumer#consume`** runs indefinitely - the stream never completes on its own. Interrupt the fiber to stop delivery and release the push subscription:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val runConsumer: ZIO[JetStream, NatsError, Unit] =
  for {
    js       <- ZIO.service[JetStream]
    consumer <- js.consumer("ORDERS", "processor")
    fiber    <- consumer.consume[String]()
                  .mapZIO(env => ZIO.debug(env.value) *> env.message.ack)
                  .runDrain
                  .fork
    _        <- ZIO.sleep(10.seconds)
    _        <- fiber.interrupt  // closes the push subscription
  } yield ()
```

**`Consumer#fetch`** completes naturally once all requested messages have been delivered or `expiresIn` elapses. The fetch consumer is released at that point without any explicit interrupt:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val batchAndStop: ZIO[JetStream, NatsError, Unit] =
  for {
    js       <- ZIO.service[JetStream]
    consumer <- js.consumer("ORDERS", "processor")
    _        <- consumer.fetch[String](FetchOptions(maxMessages = 50, expiresIn = 5.seconds))
                  .mapZIO(env => ZIO.debug(env.value) *> env.message.ack)
                  .runDrain  // stream completes once the batch is exhausted
  } yield ()
```

## The Service Framework

`NatsService` is the one service in zio-nats that is not obtained via a `ZLayer`. `nats.service(config, endpoints)` returns a `ZIO[Scope, NatsError, NatsService]` - the `Scope` in the return type is what ties the service lifetime to the enclosing block. When the scope closes, `stop()` is called and the service stops accepting new requests.

Use `ZIO.scoped` to control exactly how long the service runs. The service lives for the duration of the scoped block and is stopped automatically when the block exits:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.service.*

val runService: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    ZIO.scoped {
      for {
        svc <- nats.service(ServiceConfig(name = "inventory", version = "1.0.0"))
        _   <- ZIO.logInfo(s"Service started: ${svc.id}")
        _   <- ZIO.never  // keep the service running until the fiber is interrupted
      } yield ()
    }
  }
```

:::tip
`ZIO.never` inside a scoped block is the standard pattern for a service that should run for the lifetime of the application. When the outer fiber is interrupted, the scope closes and the service shuts down.
:::

## Watch streams

`KeyValue#watch` and `ObjectStore#watch` are indefinite streams backed by NATS push subscriptions. Like all subscriptions, the underlying NATS subscription is cancelled when the stream is interrupted or completes. Fork them alongside your program and interrupt when you are done:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val watchBucket: ZIO[Nats, NatsError, Unit] =
  for {
    kv    <- KeyValue.bucket("config")
    fiber <- kv.watchAll[String](KeyValueWatchOptions())
                .collect { case KvEvent.Put(env) => env.value }
                .tap(v => ZIO.debug(s"Changed: $v"))
                .runDrain
                .fork
    _     <- ZIO.sleep(30.seconds)
    _     <- fiber.interrupt  // unsubscribes from config bucket changes
  } yield ()
```

## Next steps

- [Layer Construction](./01-construction.md) - how services are wired together
- [Connection Events guide](../guides/07-connection-events.md) - observe connection state changes over the lifetime of the `Nats` service
- [Configuration guide](../guides/08-configuration.md) - `drainTimeout` and other shutdown-related settings
