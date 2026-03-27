---
id: connection-events
title: Connection Events
---

`Nats#lifecycleEvents` is a `ZStream[Any, Nothing, NatsEvent]` that fires whenever the underlying TCP connection changes state - connecting, disconnecting, reconnecting, and closing. The stream never fails; exceptions from the client internals are wrapped in `NatsEvent.ExceptionOccurred` rather than surfacing as stream errors. Multiple concurrent subscribers are supported and each receives a full independent copy of the event stream.

## Listening to events

Fork `lifecycleEvents` alongside your program to observe every state change without interrupting your main effect. The stream runs for the lifetime of the `Nats` service and is automatically cleaned up when the scope closes.

Use it whenever you want a persistent log of connectivity changes - for debugging flaky network conditions, feeding a metrics dashboard, or triggering alerts on disconnect:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val withEvents: ZIO[Nats, NatsError, Unit] =
  for {
    nats <- ZIO.service[Nats]
    _    <- nats.lifecycleEvents
               .tap(e => ZIO.logInfo(s"[nats] $e"))
               .runDrain
               .fork
    _    <- nats.publish(Subject("hello"), "world")
  } yield ()
```

## Reacting to specific events

`NatsEvent` is a sealed enum - use `.collect` on the stream to filter and react to individual event types. `.collect` acts as both a filter and a map: only cases matching the partial function are emitted, transformed to its return type.

Use this pattern to wire connection state into your application logic - log a warning on disconnect, increment a reconnect counter for metrics, or initiate a graceful migration when the server signals lame-duck mode:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val reactToEvents: ZIO[Nats, NatsError, Unit] =
  for {
    nats <- ZIO.service[Nats]

    _ <- nats.lifecycleEvents
           .collect { case NatsEvent.Disconnected(url) => url }
           .tap(url => ZIO.logWarning(s"Lost connection to $url - reconnecting"))
           .runDrain
           .fork

    _ <- nats.lifecycleEvents
           .collect { case NatsEvent.Reconnected(url) => url }
           .tap(url => ZIO.logInfo(s"Reconnected to $url"))
           .runDrain
           .fork

    _ <- nats.lifecycleEvents
           .takeUntil(_ == NatsEvent.Closed)
           .tap(e => ZIO.logError(s"Fatal: connection closed - $e"))
           .runDrain
           .fork
  } yield ()
```

## Event reference

| Event | When it fires |
|-------|---------------|
| `Connected(url)` | Initial connection established |
| `Disconnected(url)` | Connection lost; a reconnect attempt will follow |
| `Reconnected(url)` | TCP connection restored |
| `Resubscribed(url)` | All subscriptions re-sent to the server after reconnect |
| `ServersDiscovered` | New cluster member discovered via server gossip |
| `Closed` | Connection permanently closed - max reconnects exceeded |
| `LameDuckMode` | Server is draining connections for a graceful shutdown |
| `Error(message)` | Non-fatal error string reported by the server |
| `ExceptionOccurred(ex)` | Exception raised by the client internals |

`Disconnected` and `Reconnected` fire in pairs during a temporary outage. `Resubscribed` follows `Reconnected` once all `SUB` commands have been re-sent - if you care about subscription readiness rather than TCP readiness, wait for `Resubscribed`. `Closed` fires once and does not recover; treat it as a fatal signal and shut down or restart.

## Connection utilities

The `Nats` service exposes several point-in-time reads for inspecting the current connection state. Use them in health-check endpoints, startup probes, or operational dashboards to surface connectivity metrics without subscribing to the event stream:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val utilities: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      status  <- nats.status
      info    <- nats.serverInfo
      rtt     <- nats.rtt
      url     <- nats.connectedUrl
      stats   <- nats.statistics
      _       <- ZIO.debug(
                   s"status=$status url=$url rtt=$rtt " +
                   s"in=${stats.inMsgs} out=${stats.outMsgs} reconnects=${stats.reconnects}"
                 )
      _       <- nats.flush(1.second)
    } yield ()
  }
```

`Nats#flush` waits until all buffered outgoing messages have been written to the server. Call it before a planned shutdown to avoid losing in-flight publishes.

## Next steps

- [Configuration guide](./08-configuration.md) - tune reconnect behaviour, timeouts, and connection buffer sizes
