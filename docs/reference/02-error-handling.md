---
id: error-handling
title: Error Handling
---

# Error Handling

> The `NatsError` sealed ADT — every error the library can produce.

All zio-nats operations return `IO[NatsError, A]`. `NatsError` is a sealed trait with
exhaustive sub-types, so you can pattern-match without a catch-all — the compiler will warn
if you miss a case.

## Catching all errors

```scala
import zio.*
import zio.nats.*
import zio.nats.NatsError.*

nats.publish(Subject("subject"), "payload").catchAll {
  case ConnectionClosed(msg)                    => ZIO.logError(s"Connection closed: $msg")
  case Timeout(msg)                             => ZIO.logWarning(s"Timed out: $msg")
  case SerializationError(msg, _)               => ZIO.logError(s"Encode failed: $msg")
  case DecodingError(msg, _)                    => ZIO.logError(s"Decode failed: $msg")
  case JetStreamApiError(msg, code, apiCode, _) => ZIO.logError(s"JetStream API $code/$apiCode: $msg")
  case JetStreamPublishFailed(msg, _)           => ZIO.logError(s"Publish failed: $msg")
  case JetStreamConsumeFailed(msg, _)           => ZIO.logError(s"Consume failed: $msg")
  case KeyNotFound(key)                         => ZIO.logInfo(s"Key not found: $key")
  case other                                    => ZIO.logError(s"NATS error: ${other.message}")
}
```

## Error variants

| Error | Message field | When it occurs |
|---|---|---|
| `ConnectionClosed` | `msg: String` | Operation attempted on a closed connection |
| `Timeout` | `msg: String` | Request-reply or flush exceeded its timeout |
| `SerializationError` | `msg: String`, `cause: Option[Throwable]` | `NatsCodec[A].encode` failed |
| `DecodingError` | `msg: String`, `cause: Option[Throwable]` | `NatsCodec[A].decode` failed on an incoming message |
| `JetStreamApiError` | `msg`, `statusCode`, `apiCode`, `cause` | Server returned a JetStream API error |
| `JetStreamPublishFailed` | `msg: String`, `cause: Option[Throwable]` | Server rejected a JetStream publish |
| `JetStreamConsumeFailed` | `msg: String`, `cause: Option[Throwable]` | Consumer stream terminated unexpectedly |
| `KeyNotFound` | `key: String` | `kv.get(key)` returned no entry |
| `ServiceOperationFailed` | `msg: String`, `cause: Option[Throwable]` | Service framework operation failed |
| `ServiceStartFailed` | `msg: String`, `cause: Option[Throwable]` | Service framework failed to start |
| `ObjectStoreOperationFailed` | `msg: String`, `cause: Option[Throwable]` | Object Store operation failed |
| `UnexpectedError` | `msg: String`, `cause: Option[Throwable]` | Catch-all for unexpected jnats exceptions |

## Sub-sealed traits

Group errors by domain for broader pattern matches:

```scala
import zio.nats.NatsError

// Match any JetStream error
someEffect.catchSome {
  case e: NatsError.JetStreamError => ZIO.logError(s"JetStream: ${e.message}")
}

// Match any KV error
kvEffect.catchSome {
  case e: NatsError.KeyValueError => ZIO.logError(s"KV: ${e.message}")
}
```

| Sub-sealed trait | Members |
|---|---|
| `NatsError.JetStreamError` | `JetStreamApiError`, `JetStreamPublishFailed`, `JetStreamConsumeFailed` |
| `NatsError.KeyValueError` | `KeyNotFound` |
| `NatsError.ObjectStoreError` | `ObjectStoreOperationFailed` |
| `NatsError.ServiceError` | `ServiceOperationFailed`, `ServiceStartFailed` |

## Common handling patterns

**Retry on disconnect:**

```scala
import zio.*
import zio.nats.*

def withRetry[A](effect: IO[NatsError, A]): IO[NatsError, A] =
  effect.retry(
    Schedule.recurWhile[NatsError](_ == NatsError.ConnectionClosed(""))
      && Schedule.recurs(3)
      && Schedule.exponential(500.millis)
  )
```

**Surface as application error:**

```scala
import zio.*
import zio.nats.*

def publish(nats: Nats, subject: Subject, msg: String): IO[String, Unit] =
  nats.publish(subject, msg).mapError(_.message)
```

**Ignore `KeyNotFound` — treat as `None`:**

```scala
import zio.*
import zio.nats.*
import zio.nats.kv.*

def getOrNone(kv: KeyValue, key: String): IO[NatsError, Option[KeyValueEntry]] =
  kv.get(key)
```

`kv.get` already returns `IO[NatsError, Option[KeyValueEntry]]` — `None` for a missing key.
`KeyNotFound` is raised only by operations that require the key to exist (e.g. `kv.update`
with an `expectedRevision` that does not match).
