---
id: error-handling
title: Error Handling
---

`NatsError` is a sealed ADT - every error zio-nats can produce is one of the variants below. All operations return `IO[NatsError, A]`, so the compiler knows exactly which errors are possible at each call site.

## Error variants

| Variant                      | Fields                                                               | When it occurs                                              |
|------------------------------|----------------------------------------------------------------------|-------------------------------------------------------------|
| `ConnectionFailed`           | `message: String`, `cause: IOException`                             | Initial TCP connection could not be established             |
| `ConnectionClosed`           | `message: String`                                                    | Operation attempted on a closed or lost connection          |
| `AuthenticationFailed`       | `message: String`, `cause: IOException`                             | Server rejected the credentials                             |
| `Timeout`                    | `message: String`                                                    | Request-reply or flush exceeded its timeout                 |
| `PublishFailed`              | `message: String`, `cause: Throwable`                               | Core NATS publish rejected by the server                    |
| `RequestFailed`              | `message: String`, `cause: Throwable`                               | Request-reply call failed                                   |
| `SubscriptionFailed`         | `message: String`, `cause: Throwable`                               | Subscribe call rejected by the server                       |
| `SerializationError`         | `message: String`, `cause: Throwable`                               | `NatsCodec[A].encode` failed                                |
| `DecodingError`              | `message: String`, `cause: Throwable`                               | `NatsCodec[A].decode` failed on an incoming message         |
| `JetStreamApiError`          | `message: String`, `errorCode: Int`, `apiErrorCode: Int`, `cause: JetStreamApiException` | Server returned a JetStream API error |
| `JetStreamPublishFailed`     | `message: String`, `cause: Throwable`                               | Server rejected a JetStream publish                         |
| `JetStreamConsumeFailed`     | `message: String`, `cause: Throwable`                               | Consumer stream terminated unexpectedly                     |
| `KeyValueOperationFailed`    | `message: String`, `cause: Throwable`                               | KV operation failed (server error or connection issue)      |
| `KeyNotFound`                | `key: String`                                                        | A KV operation required a key that does not exist           |
| `ObjectStoreOperationFailed` | `message: String`, `cause: Throwable`                               | Object Store operation failed                               |
| `ObjectNotFound`             | `name: String`                                                       | Object Store `get` for a name that does not exist           |
| `ObjectAlreadyExists`        | `name: String`                                                       | Object Store `put` for a name that is already sealed        |
| `ServiceOperationFailed`     | `message: String`, `cause: Throwable`                               | Service framework runtime operation failed                  |
| `ServiceStartFailed`         | `message: String`, `cause: Throwable`                               | Service framework failed to start                           |
| `ServiceCallFailed`          | `message: String`, `code: Int`                                      | Remote service handler responded with an error              |
| `GeneralError`               | `message: String`, `cause: Throwable`                               | Catch-all for unexpected jnats exceptions                   |

All variants except `KeyNotFound`, `ObjectNotFound`, `ObjectAlreadyExists`, and `ServiceCallFailed` carry both a `message: String` and a `cause: Throwable`. `ServiceCallFailed` carries `message` and `code: Int` (the numeric error code from the remote service). Every variant is a case class, so they can be pattern-matched exhaustively.

## Sub-sealed traits

Group errors by domain for broader pattern matches. Use these when you want to handle an entire feature area uniformly rather than listing each variant:

```scala
import zio.*
import zio.nats.NatsError

// Catch any JetStream error
someEffect.catchSome {
  case e: NatsError.JetStreamError => ZIO.logError(s"JetStream: ${e.message}")
}

// Catch any KV error
kvEffect.catchSome {
  case e: NatsError.KeyValueError => ZIO.logError(s"KV: ${e.message}")
}
```

| Sub-sealed trait              | Members                                                                         |
|-------------------------------|---------------------------------------------------------------------------------|
| `NatsError.JetStreamError`    | `JetStreamApiError`, `JetStreamPublishFailed`, `JetStreamConsumeFailed`         |
| `NatsError.KeyValueError`     | `KeyValueOperationFailed`, `KeyNotFound`                                        |
| `NatsError.ObjectStoreError`  | `ObjectStoreOperationFailed`, `ObjectNotFound`, `ObjectAlreadyExists`           |
| `NatsError.ServiceError`      | `ServiceOperationFailed`, `ServiceStartFailed`, `ServiceCallFailed`             |

## Common handling patterns

**Log and continue** - use `message` which is defined on all variants:

```scala
import zio.*
import zio.nats.*

effect.catchAll(e => ZIO.logError(s"NATS error: ${e.message}"))
```

**Retry on connection loss:**

```scala mdoc:compile-only
import zio.*
import zio.nats.*

def withRetry[A](effect: IO[NatsError, A]): IO[NatsError, A] =
  effect.retry(
    Schedule.recurWhile[NatsError] { case _: NatsError.ConnectionClosed => true; case _ => false }
      && Schedule.recurs(3)
      && Schedule.exponential(500.millis)
  )
```

**Map to a simpler error type:**

```scala mdoc:compile-only
import zio.*
import zio.nats.*

def publish(nats: Nats, subject: Subject, msg: String): IO[String, Unit] =
  nats.publish(subject, msg).mapError(_.message)
```

**Handle missing keys** - `KeyValue#get` returns `Option[KvEnvelope[A]]` where `None` means the key was never written or was purged. `KeyNotFound` is raised by operations that require the key to already exist, such as `KeyValue#update` with an expected revision:

```scala
import zio.*
import zio.nats.*

// Returns None for a missing key - no KeyNotFound to handle
val value: IO[NatsError, Option[KvEnvelope[String]]] =
  kv.get[String]("my-key")

// Raises KeyNotFound if the key does not exist
val updated: IO[NatsError, Long] =
  kv.update("my-key", "new-value", expectedRevision = 5L)
```

**Handle service call errors (typed)** - use `Nats#requestService` with a shared `ServiceEndpoint` descriptor. Domain errors (`Err`) and transport failures (`NatsError`) both go into the ZIO error channel as `IO[NatsError | Err, Out]`. `NatsCodec[Err]` must be in scope:

```scala
import zio.*
import zio.nats.*

// Endpoint shared between server and client
val ep = ServiceEndpoint[String, String]("do-thing").withError[String]

// IO[String | NatsError, String] - catchSome by type
val result: IO[NatsError, Option[String]] =
  nats.requestService(ep, "input", 5.seconds)
    .map(Some(_))
    .catchSome { case _: String => ZIO.succeed(None) }
```

**Handle service call errors (untyped)** - when the endpoint descriptor is not available, `Nats#request` still detects the `Nats-Service-Error` header and fails with `ServiceCallFailed`. This covers infrastructure errors from `Nats#requestService` too:

```scala
import zio.*
import zio.nats.*

val result: IO[NatsError, Option[String]] =
  nats.request[String, String](Subject("my-service.do-thing"), "input", 5.seconds)
    .map(env => Some(env.value))
    .catchSome {
      case NatsError.ServiceCallFailed(msg, code) =>
        ZIO.logWarning(s"Service error $code: $msg").as(None)
    }
```
