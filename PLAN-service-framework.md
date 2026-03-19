# Implementation Plan: NATS Service Framework (Micro Protocol)

## Overview

The [NATS Service Framework](https://docs.nats.io/nats-concepts/service_infrastructure/services) (also called "NATS Micro") lets you register request-reply endpoints as named, versioned microservices with built-in discovery, health monitoring, and statistics. It is built entirely on core NATS request-reply — no JetStream dependency.

The jnats library (2.25.2) ships a complete implementation in the `io.nats.service` package. This plan describes how to wrap it idiomatically for ZIO in the `zio-nats` library, using a **typed endpoint pattern** inspired by ZIO-HTTP's `Endpoint` API.

**Scope:** ~5 new files, 3 edits. Comparable to the ObjectStore feature.

---

## jnats API Surface Reference

Everything lives in `io.nats.service`. The key classes and their roles:

### Core

| Class | Role |
|---|---|
| `Service` | The running service instance. Created via `Service.builder()`. Has `startService(): CompletableFuture<Boolean>`, `stop()`, and accessors for ping/info/stats responses. |
| `ServiceEndpoint` | Binds an `Endpoint` + `Group` + `ServiceMessageHandler` together. Created via `ServiceEndpoint.builder()`. |
| `Endpoint` | Name + subject + queue group + metadata for a single endpoint. Default queue group is `"q"`. |
| `Group` | A subject prefix for organizing endpoints (e.g., group `"sort"` + endpoint subject `"ascending"` → actual subject `"sort.ascending"`). Supports chaining via `appendGroup()`. |

### Handler

| Class | Role |
|---|---|
| `ServiceMessageHandler` | Functional interface: `void onMessage(ServiceMessage msg)`. This is the callback jnats invokes for each incoming request. |
| `ServiceMessage` | Wraps the incoming NATS message. Provides `getData()`, `getSubject()`, `getHeaders()`, and response methods: `respond(Connection, byte[])`, `respond(Connection, String)`, `respondStandardError(Connection, String, int)`. |

### Discovery & Responses

| Class | Role |
|---|---|
| `Discovery` | Client-side utility. Sends requests to `$SRV.PING`, `$SRV.INFO`, `$SRV.STATS` subjects. All methods are synchronous/blocking with configurable timeout and max results. |
| `PingResponse` | Service ID, name, version, metadata. Type: `io.nats.micro.v1.ping_response`. |
| `InfoResponse` | Extends ping with description and endpoint list. Type: `io.nats.micro.v1.info_response`. |
| `StatsResponse` | Extends ping with start time and per-endpoint stats. Type: `io.nats.micro.v1.stats_response`. |
| `EndpointStats` | Per-endpoint: name, subject, queue group, request count, error count, processing time (nanos), average processing time, last error, custom data (`JsonValue`), started timestamp. |

### Constants

| Constant | Value | Used for |
|---|---|---|
| `Service.SRV_PING` | `"PING"` | Discovery ping action |
| `Service.SRV_INFO` | `"INFO"` | Discovery info action |
| `Service.SRV_STATS` | `"STATS"` | Discovery stats action |
| `Service.DEFAULT_SERVICE_PREFIX` | `"$SRV."` | Subject prefix for all discovery |
| `Endpoint.DEFAULT_QGROUP` | `"q"` | Default queue group for load balancing |

---

## Design Decisions

### Typed Endpoint Pattern (ZIO-HTTP–inspired)

The central design is a **declarative, typed endpoint descriptor** that separates the endpoint's shape (input/output types, subject, metadata) from its implementation (the handler function). This is directly inspired by ZIO-HTTP's `Endpoint` API.

**Why typed endpoints over untyped `ServiceRequest => IO[NatsError, Unit]` handlers:**
- **Compile-time safety:** The handler *must* accept `In` and return `Out` — no forgetting to respond, no wrong type, no accidentally decoding as the wrong type.
- **Framework-managed serialization:** The framework decodes the request and encodes the response using the `NatsCodec`s captured on the endpoint descriptor. User code never calls `decode`/`respond` manually.
- **Sealed error channel:** The `implement` method returns a `BoundEndpoint` with no error channel — errors are handled by the framework using a `ServiceErrorMapper`, converting them to NATS service error responses. This matches ZIO-HTTP's `Route[Env, Nothing]` pattern.
- **Declarative introspection:** The endpoint descriptor can be inspected for documentation, code generation, or schema export without executing any handler code.

**How this differs from ZIO-HTTP:**
- **No type accumulation.** ZIO-HTTP uses `Combiner` to merge path + query + header + body into one `Input` type. NATS requests have a single payload, so `ServiceEndpoint[In, Out]` has just two type parameters — no `Combiner` needed.
- **No media type negotiation.** NATS uses whatever codec you configured.
- **Simpler error model.** NATS service errors are always `(String, Int)` — a message and a code. There's no structured error body in the NATS service protocol. So instead of an error codec, we use a `ServiceErrorMapper[Err]` that extracts the message and code.

### Handler Bridge: Runtime Capture with Fiber Fork

The central challenge is bridging jnats' synchronous `ServiceMessageHandler` callback to ZIO effects.

**Chosen approach:** Capture the ZIO `Runtime` at service-creation time, then **fork a fiber** inside the jnats callback. The fiber runs on the ZIO executor, leaving the jnats dispatcher thread unblocked.

```scala
val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
  Unsafe.unsafeCompat { implicit unsafe =>
    runtime.unsafe.fork(
      for {
        in  <- inCodec.decode(Chunk.fromArray(jMsg.getData))
        out <- userHandler(in).mapError(errorMapper.toErrorResponse)
        enc <- outCodec.encode(out)
        _   <- ZIO.attempt(jMsg.respond(conn, enc.toArray))
      } yield ()
    )
  }
}
```

**Why fork instead of `runtime.unsafe.run().getOrThrowFiberFailure()`:**

ZIO-HTTP's Netty bridge showed the way: they try synchronous execution first and fork a fiber if the effect suspends. They never block server threads. Similarly, we should not block jnats dispatcher threads:

- `runtime.unsafe.run` blocks the jnats dispatcher thread until the handler completes. If a handler does a DB call or another NATS request, the dispatcher thread is stuck.
- `runtime.unsafe.fork` returns immediately. The handler runs on the ZIO runtime's executor. The jnats dispatcher thread is free to process the next request.
- The response is sent asynchronously via `jMsg.respond(conn, bytes)`, which is just a publish to the reply-to subject — safe to call from any thread.
- Error handling is done inside the fiber via `.catchAll`, so unhandled errors still produce a NATS error response.

**Why this over ZStream per endpoint:**
- Service endpoints are request-reply (one response per request), not streaming
- A stream-based API would force the user to process requests sequentially or manage concurrency manually
- The handler approach is what developers expect from a service framework
- It matches jnats' model directly, minimizing impedance mismatch

### Lifecycle: Scope-managed

A `NatsService` is created within a `Scope`. When the scope closes, the service stops with drain. This matches how `Nats.make` manages the connection.

```scala
ZIO.scoped {
  for {
    service <- nats.service(config, endpoints)
    _       <- service.stats.debug("initial stats")
    _       <- ZIO.never // keep running until interrupted
  } yield ()
}
```

### Error Handling Strategy

Errors are handled at two levels:

1. **Typed domain errors via `ServiceErrorMapper`:** The user's handler can fail with any error type `Err` that has a `ServiceErrorMapper[Err]` instance. The mapper converts `Err` to `(message: String, code: Int)`, which the framework sends as a standard NATS service error response (setting `Nats-Service-Error` and `Nats-Service-Error-Code` headers).

2. **Decoding/encoding failures:** If the framework fails to decode the incoming request or encode the outgoing response, it sends a 500 error response automatically.

The user never needs to call `respondError` — the framework does it. But they can still throw domain-specific error codes by defining their `ServiceErrorMapper`.

---

## Public API

### Package Structure

```
zio.nats.service/
  ServiceEndpoint.scala     — Typed endpoint descriptor + BoundEndpoint
  NatsService.scala         — Service trait + Live + companion
  ServiceDiscovery.scala    — Discovery trait + Live
  ServiceConfig.scala       — ServiceConfig, ServiceGroup, QueueGroupPolicy, ServiceErrorMapper
  ServiceModels.scala       — ServiceRequest[A], response types, EndpointStats
```

Everything re-exported from `package object nats` so `import zio.nats.*` is sufficient.

### Config Types

```scala
package zio.nats.service

/**
 * Configuration for a NATS microservice.
 *
 * @param name         Service name (alphanumeric, hyphens, underscores only)
 * @param version      Semantic version string (e.g. "1.0.0")
 * @param description  Optional human-readable description
 * @param metadata     Optional key-value metadata exposed via discovery
 * @param drainTimeout Timeout for draining subscriptions on stop (default 5s)
 */
final case class ServiceConfig(
  name: String,
  version: String,
  description: Option[String] = None,
  metadata: Map[String, String] = Map.empty,
  drainTimeout: Option[Duration] = None
)

/**
 * A subject prefix group for organizing service endpoints.
 *
 * Groups are prepended to endpoint subjects. They can be nested:
 * {{{
 * val api  = ServiceGroup("api")
 * val v2   = ServiceGroup("v2", parent = Some(api))
 * // endpoint subject "users" becomes "api.v2.users"
 * }}}
 */
final case class ServiceGroup(
  name: String,
  parent: Option[ServiceGroup] = None
)

/**
 * Controls queue group behavior for an endpoint.
 *
 * - [[Default]] uses the standard NATS service queue group `"q"` for load balancing.
 * - [[Disabled]] disables queue grouping (every instance receives every request).
 * - [[Custom]] uses a user-specified queue group name.
 */
enum QueueGroupPolicy:
  case Default
  case Disabled
  case Custom(name: String)
```

### ServiceErrorMapper

```scala
package zio.nats.service

/**
 * Typeclass for converting handler error types to NATS service error responses.
 *
 * NATS service errors are a `(message, code)` pair sent via the
 * `Nats-Service-Error` and `Nats-Service-Error-Code` response headers.
 *
 * Built-in instances are provided for [[NatsError]] and [[String]].
 * Define your own for domain error types:
 *
 * {{{
 * enum UserError:
 *   case NotFound(id: Int)
 *   case InvalidInput(msg: String)
 *
 * given ServiceErrorMapper[UserError] with
 *   def toErrorResponse(e: UserError): (String, Int) = e match
 *     case UserError.NotFound(id)      => (s"User $id not found", 404)
 *     case UserError.InvalidInput(msg) => (msg, 400)
 * }}}
 */
trait ServiceErrorMapper[E]:
  /** Convert an error to a NATS service error `(message, code)` pair. */
  def toErrorResponse(e: E): (String, Int)

object ServiceErrorMapper:
  /** Default mapper for [[NatsError]]: sends the error message with code 500. */
  given ServiceErrorMapper[NatsError] with
    def toErrorResponse(e: NatsError): (String, Int) = (e.message, 500)

  /** Mapper for plain strings: sends the string with code 500. */
  given ServiceErrorMapper[String] with
    def toErrorResponse(e: String): (String, Int) = (e, 500)

  /** Mapper for `Nothing`: trivially satisfied, never invoked. */
  given ServiceErrorMapper[Nothing] with
    def toErrorResponse(e: Nothing): (String, Int) = throw new AssertionError("unreachable")
```

### ServiceEndpoint — The Typed Descriptor

```scala
package zio.nats.service

/**
 * A declarative, typed descriptor for a NATS service endpoint.
 *
 * Captures the endpoint's name, subject, queue group policy, metadata, and
 * — crucially — the input and output types with their [[NatsCodec]] instances.
 * The descriptor is inert: it describes the shape of an endpoint but contains
 * no handler logic.
 *
 * To create a running endpoint, call [[implement]] to bind a handler function:
 *
 * {{{
 * // 1. Declare the endpoint shape
 * val lookup = ServiceEndpoint[UserQuery, UserResponse]("lookup")
 *
 * // 2. Bind a handler — the framework handles decode/encode
 * val bound = lookup.implement { query =>
 *   ZIO.succeed(UserResponse(query.id, "Alice", "alice@example.com"))
 * }
 *
 * // 3. Register on a service
 * nats.service(ServiceConfig("users", "1.0.0"), bound)
 * }}}
 *
 * @tparam In  The request payload type (decoded from bytes via `NatsCodec[In]`)
 * @tparam Out The response payload type (encoded to bytes via `NatsCodec[Out]`)
 */
final case class ServiceEndpoint[In, Out](
  name: String,
  subject: Option[String] = None,
  queueGroup: QueueGroupPolicy = QueueGroupPolicy.Default,
  group: Option[ServiceGroup] = None,
  metadata: Map[String, String] = Map.empty
)(using val inCodec: NatsCodec[In], val outCodec: NatsCodec[Out]):

  /**
   * Bind a handler function to this endpoint, producing a [[BoundEndpoint]].
   *
   * The framework:
   * - Decodes incoming bytes as `In` using the captured `NatsCodec[In]`
   * - Passes the decoded value to `handler`
   * - Encodes the `Out` result using the captured `NatsCodec[Out]`
   * - Sends the encoded bytes as the NATS reply
   *
   * If the handler fails with `Err`, the `ServiceErrorMapper[Err]` converts
   * it to a NATS service error response. If decoding fails, a 500 error
   * is sent automatically.
   *
   * {{{
   * val echo = ServiceEndpoint[String, String]("echo")
   * val bound = echo.implement(name => ZIO.succeed(s"Hello, $name!"))
   * }}}
   */
  def implement[Err: ServiceErrorMapper](
    handler: In => IO[Err, Out]
  ): BoundEndpoint

  /**
   * Bind a handler that also receives request metadata (subject, headers).
   *
   * Use this when you need access to NATS headers or the actual subject
   * the request arrived on (e.g., for wildcard subjects):
   *
   * {{{
   * val ep = ServiceEndpoint[String, String]("events.>")
   *
   * val bound = ep.implementWithRequest { req =>
   *   val topic = req.subject.value  // e.g. "events.user.created"
   *   val traceId = req.headers.get("X-Trace-Id")
   *   ZIO.succeed(s"Processed ${req.value} on $topic")
   * }
   * }}}
   */
  def implementWithRequest[Err: ServiceErrorMapper](
    handler: ServiceRequest[In] => IO[Err, Out]
  ): BoundEndpoint
```

### BoundEndpoint — The Type-Erased, Ready-to-Register Unit

```scala
package zio.nats.service

/**
 * An endpoint with its handler bound, ready to be registered on a [[NatsService]].
 *
 * Created by calling [[ServiceEndpoint.implement]] or
 * [[ServiceEndpoint.implementWithRequest]]. The input/output types are
 * erased at this level — the codec and handler are captured inside.
 * This allows multiple `BoundEndpoint`s with different type signatures
 * to be passed together to [[Nats.service]].
 *
 * Users never construct this directly.
 */
trait BoundEndpoint:
  /** The endpoint name (for introspection/logging). */
  def name: String

  /** Build the jnats ServiceEndpoint. Internal use only. */
  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint
```

### ServiceRequest — Typed Request Wrapper

```scala
package zio.nats.service

/**
 * An incoming service request with the payload already decoded as `A`.
 *
 * Provides access to request metadata (subject, headers) alongside the
 * decoded value. Used with [[ServiceEndpoint.implementWithRequest]] when
 * the handler needs more than just the payload.
 *
 * For the common case where only the payload is needed, use
 * [[ServiceEndpoint.implement]] instead — it receives `A` directly.
 *
 * @tparam A The decoded payload type
 */
final case class ServiceRequest[+A](
  /** The decoded request payload. */
  value: A,
  /** The subject this request was sent to. */
  subject: Subject,
  /** Request headers (empty if no headers were sent). */
  headers: Headers
)
```

### NatsService Trait

The handle returned after starting a service. Used for introspection and lifecycle.

```scala
package zio.nats.service

/**
 * A running NATS microservice.
 *
 * Obtained via [[Nats.service]]. The service is automatically stopped
 * (with drain) when the enclosing [[Scope]] ends.
 *
 * Provides access to the service's identity, health, and statistics —
 * the same information that remote clients can query via [[ServiceDiscovery]].
 */
trait NatsService {

  /** Unique instance ID (generated by jnats, stable for the lifetime of this service). */
  def id: String

  /** Service name as provided in [[ServiceConfig]]. */
  def name: String

  /** Service version as provided in [[ServiceConfig]]. */
  def version: String

  /** Service description, if provided. */
  def description: Option[String]

  /** This service's ping response (name, version, id, metadata). */
  def ping: UIO[PingResponse]

  /** This service's info response (ping + description + endpoint list). */
  def info: UIO[InfoResponse]

  /**
   * This service's current statistics.
   *
   * Includes per-endpoint request counts, error counts, processing times,
   * and any custom stats data supplied via endpoint configuration.
   */
  def stats: IO[NatsError, StatsResponse]

  /** Get statistics for a specific endpoint by name. */
  def endpointStats(endpointName: String): IO[NatsError, Option[EndpointStats]]

  /** Reset all endpoint statistics counters. */
  def reset: IO[NatsError, Unit]
}
```

### Response & Stats Models

```scala
package zio.nats.service

/** Ping response from a NATS microservice. */
final case class PingResponse(
  id: String,
  name: String,
  version: String,
  metadata: Map[String, String]
)
object PingResponse {
  private[nats] def fromJava(j: JPingResponse): PingResponse
}

/** Info response from a NATS microservice. */
final case class InfoResponse(
  id: String,
  name: String,
  version: String,
  description: Option[String],
  metadata: Map[String, String],
  endpoints: List[EndpointInfo]
)
object InfoResponse {
  private[nats] def fromJava(j: JInfoResponse): InfoResponse
}

/** Describes a single endpoint in an [[InfoResponse]]. */
final case class EndpointInfo(
  name: String,
  subject: String,
  queueGroup: String,
  metadata: Map[String, String]
)

/** Statistics response from a NATS microservice. */
final case class StatsResponse(
  id: String,
  name: String,
  version: String,
  metadata: Map[String, String],
  started: java.time.ZonedDateTime,
  endpoints: List[EndpointStats]
)
object StatsResponse {
  private[nats] def fromJava(j: JStatsResponse): StatsResponse
}

/** Per-endpoint statistics. */
final case class EndpointStats(
  name: String,
  subject: String,
  queueGroup: String,
  numRequests: Long,
  numErrors: Long,
  processingTimeNanos: Long,
  averageProcessingTimeNanos: Long,
  lastError: Option[String],
  started: java.time.ZonedDateTime
)
object EndpointStats {
  private[nats] def fromJava(j: JEndpointStats): EndpointStats
}
```

### ServiceDiscovery Trait

Client-side discovery of running services across the NATS cluster.

```scala
package zio.nats.service

/**
 * Client for discovering NATS microservices.
 *
 * Sends requests to the well-known `$SRV.*` subjects and collects responses
 * from all running services (or a named subset).
 *
 * ==Example==
 * {{{
 * for {
 *   discovery <- ServiceDiscovery.make(maxWait = 3.seconds, maxResults = 50)
 *   services  <- discovery.ping()
 *   _         <- ZIO.foreach(services)(s => Console.printLine(s"${s.name} v${s.version} [${s.id}]"))
 * } yield ()
 * }}}
 */
trait ServiceDiscovery {

  // --- Ping ---

  /** Ping all services. Returns one [[PingResponse]] per running service instance. */
  def ping(): IO[NatsError, List[PingResponse]]

  /** Ping all instances of the named service. */
  def ping(serviceName: String): IO[NatsError, List[PingResponse]]

  /** Ping a specific service instance by name and ID. Returns [[None]] if not found. */
  def ping(serviceName: String, serviceId: String): IO[NatsError, Option[PingResponse]]

  // --- Info ---

  /** Get info from all services. */
  def info(): IO[NatsError, List[InfoResponse]]

  /** Get info from all instances of the named service. */
  def info(serviceName: String): IO[NatsError, List[InfoResponse]]

  /** Get info from a specific service instance. */
  def info(serviceName: String, serviceId: String): IO[NatsError, Option[InfoResponse]]

  // --- Stats ---

  /** Get stats from all services. */
  def stats(): IO[NatsError, List[StatsResponse]]

  /** Get stats from all instances of the named service. */
  def stats(serviceName: String): IO[NatsError, List[StatsResponse]]

  /** Get stats from a specific service instance. */
  def stats(serviceName: String, serviceId: String): IO[NatsError, Option[StatsResponse]]
}

object ServiceDiscovery {

  /**
   * Create a [[ServiceDiscovery]] client.
   *
   * @param maxWait    Maximum time to wait for responses (default 5 seconds)
   * @param maxResults Maximum number of responses to collect (default 10)
   */
  def make(
    maxWait: Duration = 5.seconds,
    maxResults: Int = 10
  ): ZIO[Nats, NatsError, ServiceDiscovery]

  /** ZLayer variant. */
  val live: ZLayer[Nats, NatsError, ServiceDiscovery]
}
```

### Service Creation on Nats Trait

Add to the existing `Nats` trait:

```scala
trait Nats {
  // ... existing methods ...

  /**
   * Create and start a NATS microservice.
   *
   * The service registers itself for discovery and begins handling requests
   * immediately. It is automatically stopped (with drain) when the
   * enclosing [[Scope]] ends.
   *
   * Endpoints are created by declaring a [[ServiceEndpoint]] and binding
   * a handler via [[ServiceEndpoint.implement implement]]:
   *
   * ==Example: single endpoint==
   * {{{
   * val greet = ServiceEndpoint[String, String]("greet")
   *
   * nats.service(
   *   ServiceConfig("greeter", "1.0.0"),
   *   greet.implement(name => ZIO.succeed(s"Hello, $name!"))
   * )
   * }}}
   *
   * ==Example: grouped endpoints==
   * {{{
   * val sort = ServiceGroup("sort")
   *
   * val asc  = ServiceEndpoint[List[Int], List[Int]]("ascending",  group = Some(sort))
   * val desc = ServiceEndpoint[List[Int], List[Int]]("descending", group = Some(sort))
   *
   * nats.service(
   *   ServiceConfig("sorter", "1.0.0"),
   *   asc.implement(nums  => ZIO.succeed(nums.sorted)),
   *   desc.implement(nums => ZIO.succeed(nums.sorted.reverse))
   * )
   * }}}
   *
   * @return A [[NatsService]] handle for querying stats and identity.
   */
  def service(
    config: ServiceConfig,
    endpoints: BoundEndpoint*
  ): ZIO[Scope, NatsError, NatsService]
}
```

---

## Error Model Extension

Add to `NatsError.scala`:

```scala
// --- Service errors ---

sealed trait ServiceError extends NatsError

final case class ServiceOperationFailed(message: String, cause: Throwable) extends ServiceError {
  initCause(cause)
}

final case class ServiceStartFailed(message: String, cause: Throwable) extends ServiceError {
  initCause(cause)
}
```

Update `fromThrowable` if jnats throws any service-specific exceptions (it doesn't currently — service errors surface as `IllegalArgumentException` from builders or generic `RuntimeException`).

---

## Implementation Details

### BoundEndpoint Implementation

```scala
private[nats] class BoundEndpointLive[In, Err, Out](
  val name: String,
  endpoint: ServiceEndpoint[In, Out],
  handler: ServiceRequest[In] => IO[Err, Out],
  errorMapper: ServiceErrorMapper[Err]
) extends BoundEndpoint:

  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint =
    val inCodec  = endpoint.inCodec
    val outCodec = endpoint.outCodec

    val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
      Unsafe.unsafeCompat { implicit unsafe =>
        runtime.unsafe.fork {
          val program = for {
            // Decode input
            in <- ZIO.fromEither(inCodec.decode(Chunk.fromArray(jMsg.getData)))
                    .mapError(e => ("Decoding failed: " + e.getMessage, 500))

            // Build typed request
            req = ServiceRequest(
              value   = in,
              subject = Subject(jMsg.getSubject),
              headers = Headers.fromJava(jMsg.getHeaders)
            )

            // Run user handler
            out <- handler(req)
                     .mapError(e => errorMapper.toErrorResponse(e))

            // Encode output and respond
            enc <- ZIO.fromEither(outCodec.encode(out))
                     .mapError(e => ("Encoding failed: " + e.getMessage, 500))
            _   <- ZIO.attempt(jMsg.respond(conn, enc.toArray))
                     .mapError(e => ("Response failed: " + e.getMessage, 500))
          } yield ()

          program.catchAll { case (msg, code) =>
            ZIO.attempt(jMsg.respondStandardError(conn, msg, code)).ignoreLogged
          }
        }
      }
    }

    val b = JServiceEndpoint.builder()
      .endpointName(endpoint.name)
      .handler(jHandler)

    endpoint.subject.foreach(b.endpointSubject)
    endpoint.queueGroup match
      case QueueGroupPolicy.Default     => // use default "q"
      case QueueGroupPolicy.Disabled    => b.endpointQueueGroup(null)
      case QueueGroupPolicy.Custom(qg)  => b.endpointQueueGroup(qg)

    endpoint.group.foreach(g => b.group(buildGroup(g)))
    if (endpoint.metadata.nonEmpty)
      b.endpointMetadata(endpoint.metadata.asJava)

    b.build()

  private def buildGroup(g: ServiceGroup): JGroup =
    val jGroup = new JGroup(g.name)
    g.parent.foreach(p => buildGroup(p).appendGroup(jGroup))
    g.parent.map(buildGroup).getOrElse(jGroup)
```

### ServiceEndpoint.implement

```scala
// Inside ServiceEndpoint[In, Out]

def implement[Err: ServiceErrorMapper](
  handler: In => IO[Err, Out]
): BoundEndpoint =
  new BoundEndpointLive[In, Err, Out](
    name         = name,
    endpoint     = this,
    handler      = req => handler(req.value),
    errorMapper  = summon[ServiceErrorMapper[Err]]
  )

def implementWithRequest[Err: ServiceErrorMapper](
  handler: ServiceRequest[In] => IO[Err, Out]
): BoundEndpoint =
  new BoundEndpointLive[In, Err, Out](
    name         = name,
    endpoint     = this,
    handler      = handler,
    errorMapper  = summon[ServiceErrorMapper[Err]]
  )
```

### NatsServiceLive

```scala
private[nats] class NatsServiceLive(
  jService: JService,
  conn: JConnection
) extends NatsService {

  def id: String = jService.getId
  def name: String = jService.getName
  def version: String = jService.getVersion
  def description: Option[String] = Option(jService.getDescription).filter(_.nonEmpty)

  def ping: UIO[PingResponse] =
    ZIO.succeed(PingResponse.fromJava(jService.getPingResponse))

  def info: UIO[InfoResponse] =
    ZIO.succeed(InfoResponse.fromJava(jService.getInfoResponse))

  def stats: IO[NatsError, StatsResponse] =
    ZIO.attempt(StatsResponse.fromJava(jService.getStatsResponse))
      .mapError(NatsError.fromThrowable)

  def endpointStats(endpointName: String): IO[NatsError, Option[EndpointStats]] =
    ZIO.attempt(Option(jService.getEndpointStats(endpointName)).map(EndpointStats.fromJava))
      .mapError(NatsError.fromThrowable)

  def reset: IO[NatsError, Unit] =
    ZIO.attempt(jService.reset()).mapError(NatsError.fromThrowable)
}
```

### Service Creation in NatsLive

```scala
// In NatsLive
override def service(
  config: ServiceConfig,
  endpoints: BoundEndpoint*
): ZIO[Scope, NatsError, NatsService] =
  for {
    runtime  <- ZIO.runtime[Any]
    jService <- buildAndStart(config, endpoints, runtime)
  } yield new NatsServiceLive(jService, conn)

private def buildAndStart(
  config: ServiceConfig,
  endpoints: Seq[BoundEndpoint],
  runtime: Runtime[Any]
): ZIO[Scope, NatsError, JService] =
  ZIO.acquireRelease(
    ZIO.attempt {
      val builder = JService.builder()
        .connection(conn)
        .name(config.name)
        .version(config.version)

      config.description.foreach(builder.description)
      if (config.metadata.nonEmpty)
        builder.metadata(config.metadata.asJava)
      config.drainTimeout.foreach(d => builder.drainTimeout(d.asJava))

      endpoints.foreach { ep =>
        builder.addServiceEndpoint(ep.buildJava(conn, runtime))
      }

      val svc = builder.build()
      svc.startService() // returns CompletableFuture — service is ready immediately
      svc
    }.mapError(NatsError.fromThrowable)
  )(svc => ZIO.attempt(svc.stop()).ignoreLogged)
```

### ServiceDiscoveryLive

```scala
private[nats] class ServiceDiscoveryLive(
  discovery: JDiscovery
) extends ServiceDiscovery {

  def ping(): IO[NatsError, List[PingResponse]] =
    ZIO.attemptBlocking(discovery.ping().asScala.toList.map(PingResponse.fromJava))
      .mapError(NatsError.fromThrowable)

  def ping(serviceName: String): IO[NatsError, List[PingResponse]] =
    ZIO.attemptBlocking(discovery.ping(serviceName).asScala.toList.map(PingResponse.fromJava))
      .mapError(NatsError.fromThrowable)

  def ping(serviceName: String, serviceId: String): IO[NatsError, Option[PingResponse]] =
    ZIO.attemptBlocking(Option(discovery.ping(serviceName, serviceId)).map(PingResponse.fromJava))
      .mapError(NatsError.fromThrowable)

  // info() and stats() follow the same pattern
}
```

---

## Developer Experience: End-to-End Examples

### Minimal Echo Service

```scala
import zio.*
import zio.nats.*

object MinimalServiceApp extends ZIOAppDefault {

  val echo = ServiceEndpoint[String, String]("echo")

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          svc <- nats.service(
            ServiceConfig("echo", "1.0.0"),
            echo.implement(msg => ZIO.succeed(msg)) // echo back
          )
          _ <- Console.printLine(s"Service ${svc.name} running [${svc.id}]")
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Typed JSON Service with Domain Errors

```scala
import zio.*
import zio.nats.*
import zio.schema.*

case class UserQuery(id: Int) derives Schema
case class UserResponse(id: Int, name: String, email: String) derives Schema

enum UserError:
  case NotFound(id: Int)
  case InvalidInput(msg: String)

given ServiceErrorMapper[UserError] with
  def toErrorResponse(e: UserError): (String, Int) = e match
    case UserError.NotFound(id)      => (s"User $id not found", 404)
    case UserError.InvalidInput(msg) => (msg, 400)

object UserServiceApp extends ZIOAppDefault {
  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  val lookup = ServiceEndpoint[UserQuery, UserResponse]("lookup")

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          _ <- nats.service(
            ServiceConfig("user-service", "2.1.0", description = Some("User lookup")),
            lookup.implement { query =>
              if (query.id > 0)
                ZIO.succeed(UserResponse(query.id, "Alice", "alice@example.com"))
              else
                ZIO.fail(UserError.InvalidInput("ID must be positive"))
            }
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Multi-Endpoint with Groups

```scala
import zio.*
import zio.nats.*
import zio.schema.*

object SortServiceApp extends ZIOAppDefault {
  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  val sort = ServiceGroup("sort")

  val asc  = ServiceEndpoint[List[Int], List[Int]]("ascending",  group = Some(sort))
  val desc = ServiceEndpoint[List[Int], List[Int]]("descending", group = Some(sort))

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          _ <- nats.service(
            ServiceConfig("sorter", "1.0.0"),
            asc.implement(nums  => ZIO.succeed(nums.sorted)),
            desc.implement(nums => ZIO.succeed(nums.sorted.reverse))
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Using Request Metadata (Headers, Subject)

```scala
import zio.*
import zio.nats.*

object AuditServiceApp extends ZIOAppDefault {

  val process = ServiceEndpoint[String, String]("events.>")

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          _ <- nats.service(
            ServiceConfig("auditor", "1.0.0"),
            process.implementWithRequest { req =>
              val topic   = req.subject.value      // e.g. "events.user.created"
              val traceId = req.headers.get("X-Trace-Id").getOrElse("none")
              ZIO.succeed(s"Processed ${req.value} on $topic [trace=$traceId]")
            }
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Discovery Client

```scala
import zio.*
import zio.nats.*

object DiscoveryApp extends ZIOAppDefault {
  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      for {
        discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
        services  <- discovery.ping()
        _         <- ZIO.foreach(services) { svc =>
                       Console.printLine(s"  ${svc.name} v${svc.version} [${svc.id}]")
                     }
        stats     <- discovery.stats("user-service")
        _         <- ZIO.foreach(stats) { s =>
                       ZIO.foreach(s.endpoints) { ep =>
                         Console.printLine(s"  ${ep.name}: ${ep.numRequests} reqs, ${ep.numErrors} errs")
                       }
                     }
      } yield ()
    }.provide(Nats.live, NatsConfig.default)
}
```

### Service + Client in One App (Testing Pattern)

```scala
import zio.*
import zio.nats.*
import zio.schema.*

object ServiceIntegrationTest extends ZIOAppDefault {
  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  val add = ServiceEndpoint[List[Int], String]("add")

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          // Start service
          svc <- nats.service(
            ServiceConfig("calculator", "1.0.0"),
            add.implement(nums => ZIO.succeed(nums.sum.toString))
          )

          // Call it as a client using normal request-reply
          result <- nats.request[String, String](Subject("add"), "[1,2,3]", 2.seconds)
          _      <- Console.printLine(s"Sum: ${result.value}") // "6"

          // Check stats
          stats  <- svc.stats
          _      <- Console.printLine(s"Handled ${stats.endpoints.head.numRequests} requests")
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Infallible Handler (No Errors Possible)

```scala
import zio.*
import zio.nats.*

object TimestampServiceApp extends ZIOAppDefault {

  val now = ServiceEndpoint[String, String]("now")

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          // Handler returns UIO — can't fail. The Nothing error mapper is
          // resolved automatically, so no ServiceErrorMapper is needed.
          _ <- nats.service(
            ServiceConfig("clock", "1.0.0"),
            now.implement(_ => Clock.instant.map(_.toString))
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

---

## API Comparison: Before and After

### Before (untyped)

```scala
// No compile-time safety on types. User manually decodes and responds.
// Easy to forget respond, decode wrong type, or respond with wrong type.
val handler: ServiceRequest => IO[NatsError, Unit] = { req =>
  for {
    query <- req.decode[UserQuery]       // manual decode — could be wrong type
    user   = UserResponse(query.id, "Alice", "alice@example.com")
    _     <- req.respond(user)            // manual respond — could forget this
  } yield ()
}

nats.service(
  ServiceConfig("users", "1.0.0"),
  ServiceEndpointConfig("lookup") -> handler   // types erased
)
```

### After (typed endpoints)

```scala
// Compile-time guarantee: handler MUST accept UserQuery and return UserResponse.
// Framework handles decode/encode. Can't forget to respond.
val lookup = ServiceEndpoint[UserQuery, UserResponse]("lookup")

nats.service(
  ServiceConfig("users", "1.0.0"),
  lookup.implement { query =>              // query: UserQuery (already decoded)
    ZIO.succeed(UserResponse(query.id, "Alice", "alice@example.com"))
    // ↑ return value IS the response — framework encodes and sends it
  }
)
```

---

## File Inventory

| File | Action | Contents |
|---|---|---|
| `zio-nats/src/main/scala/zio/nats/service/ServiceEndpoint.scala` | **New** | `ServiceEndpoint[In, Out]`, `BoundEndpoint`, `BoundEndpointLive` |
| `zio-nats/src/main/scala/zio/nats/service/ServiceConfig.scala` | **New** | `ServiceConfig`, `ServiceGroup`, `QueueGroupPolicy`, `ServiceErrorMapper` |
| `zio-nats/src/main/scala/zio/nats/service/ServiceModels.scala` | **New** | `ServiceRequest[A]`, `PingResponse`, `InfoResponse`, `StatsResponse`, `EndpointStats`, `EndpointInfo` |
| `zio-nats/src/main/scala/zio/nats/service/NatsService.scala` | **New** | `NatsService` trait + `NatsServiceLive` |
| `zio-nats/src/main/scala/zio/nats/service/ServiceDiscovery.scala` | **New** | `ServiceDiscovery` trait + `ServiceDiscoveryLive` + companion with `make`/`live` |
| `zio-nats/src/main/scala/zio/nats/NatsError.scala` | **Edit** | Add `ServiceError`, `ServiceOperationFailed`, `ServiceStartFailed` |
| `zio-nats/src/main/scala/zio/nats/Nats.scala` | **Edit** | Add `service()` method to trait + implementation in `NatsLive` |
| `zio-nats/src/main/scala/zio/nats/package.scala` | **Edit** | Re-export `service.*` |
| `zio-nats-test/src/test/scala/zio/nats/ServiceSpec.scala` | **New** | Integration tests |
| `examples/src/main/scala/zio/nats/examples/ServiceApp.scala` | **New** | Runnable demo |

---

## Test Plan

Tests use the existing testcontainers setup from `NatsTestLayers`.

| Test | What it validates |
|---|---|
| **Start and stop** | Service starts, is discoverable via ping, stops cleanly when scope closes |
| **Echo endpoint** | `ServiceEndpoint[String, String]` echoes back, verify round-trip |
| **Typed endpoint** | `ServiceEndpoint[UserQuery, UserResponse]` with JSON codecs, verify decode/encode |
| **Multi-endpoint** | Multiple `BoundEndpoint`s on one service, each handles correctly |
| **Grouped endpoints** | `ServiceGroup` prefixes subjects correctly |
| **Domain error mapping** | Handler fails with custom error type, `ServiceErrorMapper` produces correct code |
| **NatsError mapping** | Handler fails with `NatsError`, default mapper sends 500 |
| **Decode failure** | Bad payload sent, framework returns 500 with decode error message |
| **Infallible handler** | `UIO` handler with `Nothing` error type compiles and works |
| **Request metadata** | `implementWithRequest` receives correct subject and headers |
| **Stats** | After N requests, `endpointStats` reflects correct counts |
| **Reset** | After reset, stats counters return to zero |
| **Discovery ping** | `ServiceDiscovery.ping()` finds running services |
| **Discovery info** | `ServiceDiscovery.info()` returns endpoint list |
| **Discovery stats** | `ServiceDiscovery.stats()` returns per-endpoint stats |
| **Discovery by name** | Filtered discovery returns only matching services |
| **Multiple instances** | Two services with same name, discovery finds both |
| **Queue group load balancing** | Two instances of same endpoint, requests distributed |

---

## Notes & Caveats

- **`ServiceGroup` nesting:** jnats `Group.appendGroup` mutates and returns `this`. The `buildGroup` helper must walk the parent chain and build from root to leaf. Test carefully.
- **`QueueGroupPolicy` enum:** Replaces the awkward `Option[Option[String]]` from the initial design. `Default`, `Disabled`, `Custom(name)` are self-documenting.
- **Handler concurrency:** Each request forks its own fiber, so handlers run concurrently by default. No additional concurrency control is needed unless the user wants it (they can use `Semaphore`, `Ref`, etc. in their handler).
- **Custom stats data supplier:** jnats supports `Supplier<JsonValue>` for custom endpoint stats. Consider adding this later as an advanced option. Not needed for v1.
- **`CompletableFuture` from `startService()`:** The future completes when the service *stops*, not when it starts. The service is ready immediately after `build()` + `startService()`. Do not `await` this future during setup.
- **ZIO-HTTP comparison:** Our `ServiceEndpoint[In, Out]` + `.implement` pattern is directly inspired by ZIO-HTTP's `Endpoint[PathInput, Input, Err, Output, Auth]` + `.implement`. The key simplification is that NATS has one payload (no path/query/header accumulation via `Combiner`) and a simple error model (message + code, no structured error body via `HttpCodec`).
- **Fiber fork vs blocking run:** The initial design used `runtime.unsafe.run().getOrThrowFiberFailure()` which blocks jnats dispatcher threads. The updated design uses `runtime.unsafe.fork` (inspired by ZIO-HTTP's Netty bridge) so the dispatcher thread is free immediately. The response is sent asynchronously from the ZIO fiber.
- **Supersedes `NatsRpc`:** The existing `NatsRpc.respond[A, B](subject)(handler)` in `NatsRpc.scala` solves the same problem — typed request-reply handlers — but without discovery, monitoring, stats, grouped endpoints, queue group load balancing, or typed error mapping. Once the service framework ships, `NatsRpc` should be deprecated (and eventually removed). The migration path is straightforward: `NatsRpc.respond[A, B](subject)(handler)` becomes `ServiceEndpoint[A, B](name).implement(handler)` registered on a `NatsService`.
