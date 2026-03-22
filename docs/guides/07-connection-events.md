---
id: connection-events
title: Connection Events
---

# Connection Events

> Observe connection lifecycle — disconnects, reconnects, and errors.

`Nats.lifecycleEvents` is a `ZStream[Any, Nothing, NatsEvent]` that emits state changes for
the underlying TCP connection. Fork it alongside your program to log, alert, or react to
connectivity changes.

## Prerequisites

- [Quick start](../quickstart.md) completed

## Listening to lifecycle events

```scala mdoc:silent
import zio.*
import zio.nats.*

val withEvents: ZIO[Nats, NatsError, Unit] =
  for {
    nats <- ZIO.service[Nats]

    // Fork the event stream — it runs for the lifetime of the Nats service
    _ <- nats.lifecycleEvents
           .tap(e => ZIO.logInfo(s"[nats] $e"))
           .runDrain
           .fork

    // Your program continues here while the event stream runs in the background
    _ <- nats.publish(Subject("hello"), "world")
  } yield ()
```

**What's happening:**

1. `nats.lifecycleEvents` — emits `NatsEvent` values as the connection changes state. The stream never fails (error channel is `Nothing`) — all exceptions from jnats are wrapped in `NatsEvent.ExceptionOccurred`.
2. `.fork` — runs the event stream in a background fiber. When the `Nats` scope closes, the underlying subscription is cleaned up automatically.
3. Events are buffered internally so none are dropped between the connection being established and your fork starting.

## Filtering to specific events

```scala mdoc:silent
import zio.*
import zio.nats.*

val disconnectAlerts: ZIO[Nats, NatsError, Unit] =
  for {
    nats <- ZIO.service[Nats]

    _ <- nats.lifecycleEvents
           .collect { case NatsEvent.Disconnected(url) => url }
           .tap(url => ZIO.logWarning(s"Lost connection to $url"))
           .runDrain
           .fork

    _ <- nats.lifecycleEvents
           .collect { case NatsEvent.Reconnected(url) => url }
           .tap(url => ZIO.logInfo(s"Reconnected to $url"))
           .runDrain
           .fork
  } yield ()
```

`.collect` is a `ZStream` operator that acts as both a filter and a map — only events matching
the partial function are emitted, transformed to the return type of the case.

## Event reference

| Event | When it fires |
|---|---|
| `Connected(url)` | Initial connection established |
| `Disconnected(url)` | Connection lost (reconnect may follow) |
| `Reconnected(url)` | Reconnection attempt succeeded |
| `ServersDiscovered` | New cluster route discovered via gossip |
| `Closed` | Connection permanently closed (max reconnects exceeded) |
| `LameDuckMode` | Server is entering lame-duck shutdown (draining connections) |
| `Error(message)` | Non-fatal error string reported by the server |
| `ExceptionOccurred(ex)` | Exception thrown by the jnats client internals |

`Disconnected` and `Reconnected` fire in pairs when the connection is temporarily lost.
`Closed` fires once and does not recover — your program should treat it as a fatal signal.

## Connection utilities

These methods on `Nats` are available any time:

```scala mdoc:silent
import zio.*
import zio.nats.*

val utilities: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      status   <- nats.status           // URIO[Any, ConnectionStatus]
      info     <- nats.serverInfo       // IO[NatsError, NatsServerInfo]
      rtt      <- nats.rtt              // IO[NatsError, Duration]
      url      <- nats.connectedUrl     // URIO[Any, Option[String]]
      stats    <- nats.statistics       // URIO[Any, ConnectionStats]
      pending  <- nats.outgoingPendingMessageCount  // URIO[Any, Long]
      bytes    <- nats.outgoingPendingBytes         // URIO[Any, Long]
      _        <- nats.flush(1.second)  // IO[NatsError, Unit]
      _ <- ZIO.debug(
             s"status=$status rtt=$rtt msgs_in=${stats.inMsgs} msgs_out=${stats.outMsgs}"
           )
    } yield ()
  }
```

`ConnectionStats` fields: `.inMsgs`, `.outMsgs`, `.inBytes`, `.outBytes`, `.reconnects`,
`.droppedCount`, and more.

`flush(timeout)` waits until all buffered outgoing messages have been sent to the server.
Use it before shutting down to avoid losing in-flight publishes.

## Next steps

- [Configuration guide](./06-configuration.md) — tune reconnect behaviour, timeouts, and buffer sizes
- [NatsConfig reference](../reference/01-nats-config.md) — `maxReconnects`, `reconnectWait`, `drainTimeout`
