---
id: service
title: Service Framework
---

The NATS Service Framework (also called the Micro protocol) turns any set of request-reply handlers into a discoverable microservice with zero extra infrastructure. Services announce themselves over standard NATS subjects, respond to `ping`, `info`, and `stats` queries from any client on the cluster, and load-balance requests across multiple instances automatically via queue groups. There are no sidecars, no service registries, and no additional network hops - discovery and routing are built into NATS itself.

## Defining endpoints

An endpoint is built via a short builder chain. Start with `ServiceEndpoint(name)`, fix the request type with `.in[In]`, fix the reply type with `.out[Out]`, and optionally declare a domain error type with `.failsWith[Err]`. Each step changes the Scala type, so the compiler enforces the full contract before you ever write handler logic:

```
ServiceEndpoint("name")   // NamedEndpoint  - no types yet
  .in[StockRequest]        // EndpointIn     - In fixed
  .out[StockReply]         // ServiceEndpoint[StockRequest, Nothing, StockReply]
  .failsWith[StockError]   // ServiceEndpoint[StockRequest, StockError, StockReply]
```

The resulting `ServiceEndpoint[In, Err, Out]` is an inert descriptor - it carries the name, codecs, and error type, but no handler logic. This makes it easy to share the same descriptor between the server (which binds a handler) and the client (which uses it with `Nats#requestService`).

A concrete infallible endpoint looks like this:

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

val stockEndpoint = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
```

### Implementing a handler

`ServiceEndpoint#handle` binds a handler function `In => IO[Err, Out]` to the descriptor and returns a `BoundEndpoint` ready for registration. The handler receives only the decoded payload - the framework takes care of encoding, decoding, and error responses:

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

val bound = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .handle { req =>
    ZIO.succeed(StockReply(available = 42, reserved = 3))
  }
```

When you need the raw request envelope - to read a header, inspect the originating subject, or propagate a trace ID - use `ServiceEndpoint#handleWith` instead:

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

val boundWithMeta = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .handleWith { req =>
    val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
    ZIO.debug(s"Handling ${req.value.itemId} trace=$traceId") *>
      ZIO.succeed(StockReply(available = 42, reserved = 3))
  }
```

### Handling errors

When your handler can fail, add `.failsWith[E]` before `.handle`. The error type is now part of the descriptor - the same codec that encodes domain errors on the server decodes them on the client. A universal `ServiceErrorMapper[E]` (`e.toString`, code 500) is always in scope, so no extra setup is required for most types. Built-in mappers for `String` and `NatsError` are also provided:

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

val fallibleEndpoint = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .failsWith[String]
  .handle { req =>
    if (req.itemId.isEmpty) ZIO.fail("itemId must not be empty")
    else ZIO.succeed(StockReply(available = 42, reserved = 3))
  }
```

#### Custom `ServiceErrorMapper`

To control the HTTP-style status code or error message format, provide a `given ServiceErrorMapper[E]` in scope before building the endpoint. The full form uses `with`:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

enum StockError:
  case NotFound(id: String)
  case InvalidRequest(msg: String)

object StockError { given Schema[StockError] = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

given ServiceErrorMapper[StockError] with {
  def toErrorResponse(e: StockError): (String, Int) = e match {
    case StockError.NotFound(id)        => (s"Item $id not found", 404)
    case StockError.InvalidRequest(msg) => (msg, 400)
  }
}

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val ep = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .failsWith[StockError]
```

Because `ServiceErrorMapper[E]` has a single abstract method, Scala 3 also accepts a lambda (SAM syntax), which is more concise:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

enum StockError:
  case NotFound(id: String)
  case InvalidRequest(msg: String)

object StockError { given Schema[StockError] = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

given ServiceErrorMapper[StockError] = { e =>
  e match {
    case StockError.NotFound(id)        => (s"Item $id not found", 404)
    case StockError.InvalidRequest(msg) => (msg, 400)
  }
}

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }

val ep = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .failsWith[StockError]
```

Both forms produce identical behaviour. The `(message, code)` pair is sent to callers via the `Nats-Service-Error` and `Nats-Service-Error-Code` headers, following the NATS Micro protocol convention.

## Starting a service

`ServiceConfig` names the service and declares its version. Pass one or more bound endpoints to `Nats#service` to register them and start the service. The returned `NatsService` handle gives access to live stats and a reset operation:

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

val startService: ZIO[Nats & Scope, NatsError, NatsService] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.service(
      ServiceConfig(
        name        = "inventory",
        version     = "1.0.0",
        description = Some("Stock level queries")
      ),
      ServiceEndpoint("stock-check")
        .in[StockRequest]
        .out[StockReply]
        .handle { req => ZIO.succeed(StockReply(available = 42, reserved = 3)) }
    )
  }
```

The `Scope` in the return type ties the service lifetime to the enclosing scope - when the scope closes, the service shuts down and stops accepting requests. Requests arrive on the subject `<service-name>.<endpoint-name>` by default - in this case `inventory.stock-check`. Multiple instances started with the same config share a queue group automatically, so NATS distributes requests across them with no additional configuration.

**Grouping endpoints.** Call `.inGroup(name)` on a `NamedEndpoint` to prepend a subject prefix. All endpoints in the same group share a common namespace:

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

val startGrouped: ZIO[Nats & Scope, NatsError, NatsService] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.service(
      ServiceConfig(name = "catalog-service", version = "1.0.0"),
      ServiceEndpoint("stock").inGroup("catalog")
        .in[StockRequest].out[StockReply]
        .handle(_ => ZIO.succeed(StockReply(42, 3))),
      ServiceEndpoint("price").inGroup("catalog")
        .in[PriceRequest].out[PriceReply]
        .handle(_ => ZIO.succeed(PriceReply(1299)))
    )
  }
```

Both endpoints are now reachable under the `catalog` prefix: `catalog.stock` and `catalog.price`. We now have a running service with two typed, discoverable endpoints.

## Calling a service

We have three ways to call a service endpoint: a typed call with `Nats#requestService` that surfaces both domain and infrastructure errors in the ZIO error channel, a raw call with `Nats#request` using `ServiceEndpoint#effectiveSubject` for infallible endpoints, and an untyped `Nats#request` for when the endpoint descriptor is not available.

### Typed calls with `requestService`

`Nats#requestService` is the preferred way to call a fallible service endpoint. It uses the `ServiceEndpoint` descriptor as the complete contract - the subject, input and output codecs, and error codec are all captured there. Both domain errors (`Err`) and transport failures (`NatsError`) surface directly in the ZIO error channel as `IO[NatsError | Err, Out]`:

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
val stockEndpoint = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .failsWith[StockError]

val checkStock: ZIO[Nats, NatsError | StockError, Int] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.requestService(stockEndpoint, StockRequest("item-456"), 5.seconds)
      .map(_.available)
  }
```

`Nats#requestService` produces:
- `out: Out` when the handler succeeds
- Fails with `err: Err` when the handler returns a domain error
- Fails with `NatsError.ServiceCallFailed` for infrastructure errors (codec crash, connection issues)
- Fails with `NatsError.Timeout` if no reply arrives in time

Because the error type is `NatsError | Err`, Scala 3 union types compose naturally across multiple calls - no manual error merging needed:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class StockRequest(itemId: String)
case class StockReply(available: Int, reserved: Int)
case class PriceRequest(itemId: String)
case class PriceReply(cents: Long)
case class StockError(reason: String)
case class PriceError(reason: String)

object StockRequest { given Schema[StockRequest] = Schema.derived }
object StockReply   { given Schema[StockReply]   = Schema.derived }
object PriceRequest { given Schema[PriceRequest] = Schema.derived }
object PriceReply   { given Schema[PriceReply]   = Schema.derived }
object StockError   { given Schema[StockError]   = Schema.derived }
object PriceError   { given Schema[PriceError]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val stockEp = ServiceEndpoint("stock").in[StockRequest].out[StockReply].failsWith[StockError]
val priceEp = ServiceEndpoint("price").in[PriceRequest].out[PriceReply].failsWith[PriceError]

// Error type widens to NatsError | StockError | PriceError automatically
val checkBoth: ZIO[Nats, NatsError | StockError | PriceError, (StockReply, PriceReply)] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.requestService(stockEp, StockRequest("item-1"), 5.seconds)
      .zipPar(nats.requestService(priceEp, PriceRequest("item-1"), 5.seconds))
  }
```

`ServiceEndpoint#failsWith` only requires a `NatsCodec[E]` in scope - the same codec encodes the error on the server and decodes it on the client. `NatsCodec[E]` is derived automatically from the `Schema[E]` when using `NatsCodec.fromFormat`. A `ServiceErrorMapper[E]` is resolved automatically via the universal fallback; provide a specific instance to customise the `Nats-Service-Error` header value or status code.

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

val stockEndpoint = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]

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

| Call              | Returns                                                          |
|-------------------|------------------------------------------------------------------|
| `ping()`          | `List[PingResponse]` - all services in the cluster              |
| `ping(name)`      | `List[PingResponse]` - all instances of the named service       |
| `ping(name, id)`  | `Option[PingResponse]` - a specific instance by ID             |
| `info(...)`       | Same shapes, returns `InfoResponse` with endpoint list          |
| `stats(...)`      | Same shapes, returns `StatsResponse` with per-endpoint counters |

`NatsService#stats` returns the same `StatsResponse` from the server side - useful for exposing metrics from within the service process itself.

## Next steps

- [Key-Value guide](./05-key-value.md) - persistent key-value storage built on JetStream
- [Pub/Sub guide](./01-pubsub.md) - the request-reply mechanics that service endpoints are built on
