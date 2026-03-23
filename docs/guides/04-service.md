---
id: service
title: Service Framework
---

The NATS Service Framework (also called the Micro protocol) turns any set of request-reply handlers into a discoverable microservice with zero extra infrastructure. Services announce themselves over standard NATS subjects, respond to `ping`, `info`, and `stats` queries from any client on the cluster, and load-balance requests across multiple instances automatically via queue groups. There are no sidecars, no service registries, and no additional network hops - discovery and routing are built into NATS itself.

## Defining endpoints

A `ServiceEndpoint[In, Err, Out]` is a typed descriptor for a single endpoint - it carries the name, the expected request type, the reply type, and the error type, but contains no handler logic yet. Separating the shape from the implementation makes endpoints easy to share across modules or test in isolation.

`ServiceEndpoint.apply` is the two-type-parameter form for infallible handlers (handlers that cannot fail). Use `ServiceEndpoint#withError` to introduce a concrete error type when your handler can fail:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.jetstream.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")
```

### Implementing a handler

`ServiceEndpoint#implement` binds a handler function `In => IO[Err, Out]` to the endpoint and returns a `BoundEndpoint` ready for registration. Use `ServiceEndpoint#implementWithRequest` when you need the request subject or headers alongside the decoded payload:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")

val bound = stockEndpoint.implement { req =>
  ZIO.succeed(StockReply(available = 42, reserved = 3))
}
```

When you need the raw request envelope - to read a header, inspect the reply-to subject, or log the originating subject - use `ServiceEndpoint#implementWithRequest`:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")

val boundWithMeta = stockEndpoint.implementWithRequest { req =>
  val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
  ZIO.debug(s"Handling ${req.value.itemId} trace=$traceId") *>
    ZIO.succeed(StockReply(available = 42, reserved = 3))
}
```

### Handling errors

When your handler can fail, use `ServiceEndpoint#withError` to declare the error type and provide a `ServiceErrorMapper[E]`. The mapper converts your error into a `(message, code)` pair that NATS sends back as a typed service error response. Built-in mappers are provided for `NatsError` and `String`:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")

// String errors use the built-in ServiceErrorMapper[String]
val fallibleEndpoint = stockEndpoint.withError[String].implement { req =>
  if (req.itemId.isEmpty)
    ZIO.fail("itemId must not be empty")
  else
    ZIO.succeed(StockReply(available = 42, reserved = 3))
}
```

:::tip
For infallible handlers (`IO[Nothing, Out]`), Scala 3's given resolution requires an explicit `[Nothing]` type parameter: `ep.implement[Nothing](handler)`. Handlers with a concrete error type work without annotation.
:::

## Starting a service

`ServiceConfig` names the service and declares its version. Pass one or more `BoundEndpoint`s to `Nats#service` to register them and start the service. The returned `NatsService` handle gives access to live stats and a reset operation:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")
val bound = stockEndpoint.implement { req =>
  ZIO.succeed(StockReply(available = 42, reserved = 3))
}

val startService: ZIO[Nats & Scope, NatsError, NatsService] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.service(
      ServiceConfig(
        name        = "inventory",
        version     = "1.0.0",
        description = Some("Stock level queries")
      ),
      bound
    )
  }
```

The `Scope` in the return type ties the service lifetime to the enclosing scope - when the scope closes, the service shuts down and stops accepting requests. In a long-running application, provide the scope via `ZIO.scoped` and keep it open with `ZIO.never` or another blocking effect. Requests arrive on the subject `<service-name>.<endpoint-name>` by default - in this case `inventory.stock-check`. Multiple instances started with the same config share a queue group automatically, so NATS distributes requests across them with no additional configuration.

### Grouping endpoints

`ServiceGroup` adds a subject prefix that organises related endpoints under a common namespace. Nest groups to mirror a deeper subject hierarchy:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)
case class PriceRequest(itemId: String)
case class PriceReply(cents: Long)

object StockRequest  { given Schema[StockRequest]  = Schema.derived }
object StockReply    { given Schema[StockReply]    = Schema.derived }
object PriceRequest  { given Schema[PriceRequest]  = Schema.derived }
object PriceReply    { given Schema[PriceReply]    = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val catalogGroup = ServiceGroup("catalog")

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply](
  "stock",
  group = Some(catalogGroup)
)
val priceEndpoint = ServiceEndpoint[PriceRequest, PriceReply](
  "price",
  group = Some(catalogGroup)
)

val boundStock = stockEndpoint.implement(_ => ZIO.succeed(StockReply(42, 3)))
val boundPrice = priceEndpoint.implement(_ => ZIO.succeed(PriceReply(1299)))

val startGrouped: ZIO[Nats & Scope, NatsError, NatsService] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.service(
      ServiceConfig(name = "catalog-service", version = "1.0.0"),
      boundStock,
      boundPrice
    )
  }
```

Both endpoints are now reachable under the `catalog` prefix: `catalog.stock` and `catalog.price`.

## Calling a service

### Typed calls with `requestService`

`Nats#requestService` is the preferred way to call a fallible service endpoint. It uses the `ServiceEndpoint` descriptor as the complete contract - the subject, input and output codecs, and error codec are all captured there - and returns `Either[Err, Out]` to cleanly separate domain errors from transport failures:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)
case class StockError(reason: String)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }
object StockError   { given Schema[StockError]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

// Shared endpoint descriptor - the complete typed contract
val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")
  .withError[StockError]

val checkStock: ZIO[Nats, NatsError, Option[StockReply]] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.requestService(stockEndpoint, StockRequest("item-456"), 5.seconds).map {
      case Right(reply) => Some(reply)
      case Left(err)    => None  // typed domain error: StockError
    }
  }
```

`Nats#requestService` returns:
- `Right(out)` - the handler succeeded; reply body decoded as `Out`
- `Left(err)` - the handler failed with a domain `Err`; reply body decoded as `Err`
- Fails with `NatsError.ServiceCallFailed` for infrastructure errors (codec crash, connection issues)
- Fails with `NatsError.Timeout` if no reply arrives in time

For this to work, `Err` must have a `NatsCodec[Err]` in scope - the same codec is used on both sides to encode the error body on the server and decode it on the client. This is why `ServiceEndpoint#withError` now requires both `NatsCodec[E]` and `ServiceErrorMapper[E]`.

### Calling infallible endpoints

For infallible endpoints (`Err = Nothing`) there is no domain error to decode, so `Nats#requestService` does not apply. Use `Nats#request` with `ServiceEndpoint#effectiveSubject` to get the correct subject from the descriptor:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEndpoint = ServiceEndpoint[StockRequest, StockReply]("stock-check")

val checkStock: ZIO[Nats, NatsError, StockReply] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.request[StockRequest, StockReply](
      stockEndpoint.effectiveSubject,
      StockRequest("item-456"),
      timeout = 5.seconds
    ).payload
  }
```

### Untyped calls with `Nats#request`

`Nats#request` works with any subject, including service endpoints, and does not require knowledge of the endpoint's error type. When a service handler fails, it still detects the `Nats-Service-Error` header and fails with `NatsError.ServiceCallFailed(message, code)`. Use this when you do not share the endpoint descriptor or when building lower-level tooling:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val checkStock: ZIO[Nats, NatsError, Option[StockReply]] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.request[StockRequest, StockReply](
      Subject("inventory.stock-check"),
      StockRequest("item-456"),
      timeout = 5.seconds
    )
    .map(env => Some(env.value))
    .catchSome {
      case NatsError.ServiceCallFailed(msg, code) =>
        ZIO.logWarning(s"Service error $code: $msg").as(None)
    }
  }
```

The `code` field mirrors the HTTP status convention used by the NATS Micro protocol - a custom `ServiceErrorMapper` can emit any code.

## Service discovery

`ServiceDiscovery` queries all running instances of a service across the cluster by broadcasting to the `$SRV.*` subjects that NATS reserves for service metadata. It is a read-only client - use it from monitoring dashboards, health checks, or admin tooling, not from handlers themselves.

`ServiceDiscovery.live` builds the layer from an existing `Nats` connection. Call `ServiceDiscovery#ping`, `ServiceDiscovery#info`, or `ServiceDiscovery#stats` with or without a service name to scope the query:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val discoverAll: ZIO[ServiceDiscovery, NatsError, Unit] =
  ZIO.serviceWithZIO[ServiceDiscovery] { sd =>
    for {
      responses <- sd.info("inventory")
      _         <- ZIO.foreach(responses) { r =>
                     ZIO.debug(s"${r.name} v${r.version} - ${r.endpoints.map(_.name).mkString(", ")}")
                   }
    } yield ()
  }
```

The three discovery operations each have three overloads:

| Call | Returns |
|------|---------|
| `ping()` | `List[PingResponse]` - all services in the cluster |
| `ping(name)` | `List[PingResponse]` - all instances of the named service |
| `ping(name, id)` | `Option[PingResponse]` - a specific instance by ID |
| `info(...)` | Same shapes, returns `InfoResponse` with endpoint list |
| `stats(...)` | Same shapes, returns `StatsResponse` with per-endpoint counters |

`NatsService#stats` returns the same `StatsResponse` from the server side - useful for exposing metrics from within the service process itself.

## Next steps

- [Key-Value guide](./05-key-value.md) - persistent key-value storage built on JetStream
- [Pub/Sub guide](./01-pubsub.md) - the request-reply mechanics that service endpoints are built on
