---
id: service
title: Service Framework
---

The NATS Service Framework (also called the Micro protocol) turns any set of request-reply handlers into a discoverable microservice with zero extra infrastructure. Services announce themselves over standard NATS subjects, respond to `ping`, `info`, and `stats` queries from any client on the cluster, and load-balance requests across multiple instances automatically via queue groups. There are no sidecars, no service registries, and no additional network hops - discovery and routing are built into NATS itself.

## Defining endpoints

An endpoint is built via a short builder chain. Start with `ServiceEndpoint(name)`, fix the request type with `.in[In]`, fix the reply type with `.out[Out]`, and optionally declare domain error types with one or more `.failsWith[Err]` calls. Each step changes the Scala type, so the compiler enforces the full contract before you ever write handler logic:

```
ServiceEndpoint("name")          // NamedEndpoint  - no types yet
  .in[StockRequest]               // EndpointIn     - In fixed
  .out[StockReply]                // ServiceEndpoint[StockRequest, Nothing, StockReply]
  .failsWith[StockError]          // ServiceEndpoint[StockRequest, StockError, StockReply]
  .failsWith[ValidationError]     // ServiceEndpoint[StockRequest, StockError | ValidationError, StockReply]
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

When a handler needs services from the ZIO environment, use `handleZIO`. The required environment becomes part of the bound endpoint and is required by `Nats#service`:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

trait StockRepository {
  def available(itemId: String): UIO[Int]
}

case class StockRequest(itemId: String)
case class StockReply(available: Int)

given NatsCodec[StockRequest] = ???
given NatsCodec[StockReply]   = ???

val bound = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .handleZIO[StockRepository] { req =>
    ZIO.serviceWithZIO[StockRepository](_.available(req.itemId).map(StockReply(_)))
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

`handleWithZIO` combines both forms: it receives the full `ServiceRequest` and may require an environment. This is the preferred shape for generic middleware integrations such as authentication, request-scoped context, or tracing extraction.

### Handling errors

When your handler can fail, add `ServiceEndpoint#failsWith[E]` before `ServiceEndpoint#handle`. The error type is now part of the descriptor - the same codec that encodes domain errors on the server decodes them on the client. A universal `ServiceErrorMapper[E]` (`e.toString`, code 500) is always in scope, so no extra setup is required for most types. Built-in mappers for `String` and `NatsError` are also provided:

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

#### Union error types

When a handler can fail with more than one distinct error type, chain multiple `failsWith` calls — one per error member. The framework encodes the fully-qualified class name of the concrete error as a `Nats-Service-Error-Type` header on each reply, and the client dispatches decoding to the matching member codec using that tag:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class OrderRequest(itemId: String, qty: Int)
case class OrderReply(orderId: String)
case class ValidationError(field: String, reason: String)
case class PaymentError(code: Int)

object OrderRequest    { given Schema[OrderRequest]    = Schema.derived }
object OrderReply      { given Schema[OrderReply]      = Schema.derived }
object ValidationError { given Schema[ValidationError] = Schema.derived }
object PaymentError    { given Schema[PaymentError]    = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val orderEndpoint = ServiceEndpoint("order")
  .in[OrderRequest]
  .out[OrderReply]
  .failsWith[ValidationError]
  .failsWith[PaymentError]
```

The resulting descriptor has type `ServiceEndpoint[OrderRequest, ValidationError | PaymentError, OrderReply]`. On the client side, `Nats#requestService` returns `IO[NatsError | ValidationError | PaymentError, OrderReply]` — each member is decoded with its own codec and surfaces directly in the ZIO error channel:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class OrderRequest(itemId: String, qty: Int)
case class OrderReply(orderId: String)
case class ValidationError(field: String, reason: String)
case class PaymentError(code: Int)

object OrderRequest    { given Schema[OrderRequest]    = Schema.derived }
object OrderReply      { given Schema[OrderReply]      = Schema.derived }
object ValidationError { given Schema[ValidationError] = Schema.derived }
object PaymentError    { given Schema[PaymentError]    = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val orderEndpoint = ServiceEndpoint("order")
  .in[OrderRequest]
  .out[OrderReply]
  .failsWith[ValidationError]
  .failsWith[PaymentError]

val placeOrder: ZIO[Nats, NatsError | ValidationError | PaymentError, OrderReply] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.requestService(orderEndpoint, OrderRequest("item-1", 2), 5.seconds)
  }
```

Chain `failsWith` calls to accumulate as many error members as you need:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class OrderRequest(itemId: String, qty: Int)
case class OrderReply(orderId: String)
case class ValidationError(field: String, reason: String)
case class PaymentError(code: Int)
case class InventoryError(shortage: Int)
case class AuthError(reason: String)
case class RateLimitError(retryAfterSeconds: Int)
case class ConflictError(id: String)

object OrderRequest    { given Schema[OrderRequest]    = Schema.derived }
object OrderReply      { given Schema[OrderReply]      = Schema.derived }
object ValidationError { given Schema[ValidationError] = Schema.derived }
object PaymentError    { given Schema[PaymentError]    = Schema.derived }
object InventoryError  { given Schema[InventoryError]  = Schema.derived }
object AuthError       { given Schema[AuthError]       = Schema.derived }
object RateLimitError  { given Schema[RateLimitError]  = Schema.derived }
object ConflictError   { given Schema[ConflictError]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

// Three members
val ep3 = ServiceEndpoint("order")
  .in[OrderRequest].out[OrderReply]
  .failsWith[ValidationError]
  .failsWith[PaymentError]
  .failsWith[InventoryError]

// Any number of members via chaining
val ep6 = ServiceEndpoint("order-full")
  .in[OrderRequest].out[OrderReply]
  .failsWith[ValidationError]
  .failsWith[PaymentError]
  .failsWith[InventoryError]
  .failsWith[AuthError]
  .failsWith[RateLimitError]
  .failsWith[ConflictError]
```

`ServiceEndpoint#failsWith` accumulates each chained error type into the endpoint error channel, so you can keep adding members as needed. Repeating the same error type is also allowed.

If you prefer a single domain error type shared across your service boundary, you can still model the errors as a sealed enum and use the single-type `failsWith[E]` overload:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

enum OrderError:
  case ValidationError(field: String, reason: String)
  case PaymentError(code: Int)
  case InventoryError(shortage: Int)
  case AuthError(reason: String)
  case RateLimitError(retryAfterSeconds: Int)
  case ConflictError(id: String)

object OrderError { given Schema[OrderError] = Schema.derived }

given ServiceErrorMapper[OrderError] = {
  case OrderError.ValidationError(_, _)     => ("Validation failed", 400)
  case OrderError.PaymentError(_)           => ("Payment declined", 402)
  case OrderError.InventoryError(_)         => ("Insufficient stock", 409)
  case OrderError.AuthError(_)              => ("Unauthorized", 401)
  case OrderError.RateLimitError(_)         => ("Too many requests", 429)
  case OrderError.ConflictError(_)          => ("Conflict", 409)
}

case class OrderRequest(itemId: String, qty: Int)
case class OrderReply(orderId: String)

object OrderRequest { given Schema[OrderRequest] = Schema.derived }
object OrderReply   { given Schema[OrderReply]   = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val orderEndpoint = ServiceEndpoint("order")
  .in[OrderRequest]
  .out[OrderReply]
  .failsWith[OrderError]
```

A single `NatsCodec[OrderError]` covers all cases and a single `ServiceErrorMapper` pattern-matches to emit fine-grained status codes.

Each member's `ServiceErrorMapper` is resolved independently for union overloads, so you can provide a custom status code for one type and rely on the universal fallback for others. Scala 3 union types also compose silently across multiple `Nats#requestService` calls — if you call two union-typed endpoints in the same comprehension, the error channels widen into a single union type with no manual merging.

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

**Grouping endpoints.** Call `NamedEndpoint#inGroup` to prepend a subject prefix. All endpoints in the same group share a common namespace:

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

`ServiceEndpoint#failsWith` only requires a `NatsCodec[E]` in scope — the same codec encodes the error on the server and decodes it on the client. `NatsCodec[E]` is derived automatically from the `Schema[E]` when using `NatsCodec.fromFormat`. A `ServiceErrorMapper[E]` is resolved automatically via the universal fallback; provide a specific instance to customise the `Nats-Service-Error` header value or status code. For multiple distinct error types, chain `failsWith` calls as described in the [union error types](#union-error-types) section above.

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
      subject"inventory.stock-check",
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

### Sending request headers

Both `Nats#requestService` and `Nats#request` accept an optional `PublishParams` as a fourth argument, making typed service calls symmetric with `Nats#publish`. This is the idiomatic way to attach cross-cutting metadata — a trace ID, a correlation ID, or a contract version signal — to a service call without touching the payload type.

Pass a `PublishParams` after the timeout and the headers arrive at the handler via `ServiceRequest#headers`. The server side reads them using `ServiceEndpoint#handleWith` (see [Implementing a handler](#implementing-a-handler)):

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

val stockEndpoint = ServiceEndpoint("stock-check")
  .in[StockRequest]
  .out[StockReply]
  .failsWith[StockError]

val checkStock: ZIO[Nats, NatsError | StockError, Int] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.requestService(
      stockEndpoint,
      StockRequest("item-456"),
      5.seconds,
      PublishParams(headers = Headers("X-Trace-Id" -> "req-abc123"))
    ).map(_.available)
  }
```

The `replyTo` field of `PublishParams` is ignored for all request operations — NATS manages the reply inbox automatically.

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
