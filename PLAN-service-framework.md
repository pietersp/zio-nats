# Implementation Plan: NATS Service Framework (Micro Protocol)

## Overview

The [NATS Service Framework](https://docs.nats.io/nats-concepts/service_infrastructure/services) (also called "NATS Micro") lets you register request-reply endpoints as named, versioned microservices with built-in discovery, health monitoring, and statistics. It is built entirely on core NATS request-reply — no JetStream dependency.

The jnats library (2.25.2) ships a complete implementation in the `io.nats.service` package. This plan describes how to wrap it idiomatically for ZIO in the `zio-nats` library.

**Scope:** ~4 new files, 2 edits. Comparable to the ObjectStore feature.

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

### Handler Bridge: Runtime Capture

The central challenge is bridging jnats' synchronous `ServiceMessageHandler` callback to ZIO effects.

**Chosen approach:** Capture the ZIO `Runtime` at service-creation time, then use `Unsafe.run` inside the jnats callback.

```scala
// Inside NatsServiceLive construction
val runtime: Runtime[Any] = ??? // captured from ZIO.runtime[Any]

val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
  Unsafe.unsafeCompat { implicit unsafe =>
    runtime.unsafe.run(
      userHandler(ServiceRequest.fromJava(jMsg, conn))
        .catchAll(e => ZIO.logError(s"Service handler error: ${e.message}"))
    ).getOrThrowFiberFailure()
  }
}
```

**Why this over ZStream per endpoint:**
- Service endpoints are request-reply (one response per request), not streaming
- A stream-based API would force the user to process requests sequentially or manage concurrency manually
- The handler approach is what developers expect from a service framework
- It matches jnats' model directly, minimizing impedance mismatch

**Why this over a Queue-based approach:**
- Each request needs its own response — a queue adds latency and complexity for no benefit
- The handler function is the natural unit of composition

### Lifecycle: Scope-managed

A `NatsService` is created within a `Scope`. When the scope closes, the service stops with drain. This matches how `Nats.make` manages the connection.

```scala
// Usage
ZIO.scoped {
  for {
    service <- nats.service(config, endpoints)
    _       <- service.stats.debug("initial stats")
    _       <- ZIO.never // keep running until interrupted
  } yield ()
}
```

### Error Handling in Handlers

User handler errors are caught and translated to NATS error responses automatically. The user can also call `respondError` explicitly for domain-level errors:

```scala
// Automatic: unhandled NatsError becomes a 500 error response
// Explicit: user controls the error
request.respondError("User not found", 404)
```

### Typed Handlers via NatsCodec

Handlers receive a `ServiceRequest` with raw bytes. Decoding is explicit via `request.decode[A]` (consistent with how the rest of the library works — the user controls when decoding happens). Responding is generic via `request.respond[A](value)`.

---

## Public API

### Package Structure

```
zio.nats.service/
  NatsService.scala         — Service trait + Live + companion (layer, builder)
  ServiceDiscovery.scala    — Discovery trait + Live
  ServiceConfig.scala       — ServiceConfig, ServiceEndpointConfig, ServiceGroup
  ServiceModels.scala       — ServiceRequest, response types, EndpointStats
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
 * Configuration for a single service endpoint.
 *
 * @param name       Endpoint name (alphanumeric, hyphens, underscores only)
 * @param subject    NATS subject to listen on. Defaults to `name` if not specified.
 * @param queueGroup Queue group for load balancing. Defaults to `"q"`.
 *                   Set to [[None]] to disable queue grouping.
 * @param group      Optional [[ServiceGroup]] prefix
 * @param metadata   Optional key-value metadata for this endpoint
 */
final case class ServiceEndpointConfig(
  name: String,
  subject: Option[String] = None,
  queueGroup: Option[Option[String]] = None, // None = use default "q", Some(None) = no queue group, Some(Some(x)) = custom
  group: Option[ServiceGroup] = None,
  metadata: Map[String, String] = Map.empty
)
```

All config types get a `private[nats] def toJava` method for converting to jnats builders.

### ServiceRequest

The message wrapper the user's handler receives. Carries response capability.

```scala
package zio.nats.service

/**
 * An incoming service request with response capabilities.
 *
 * This is the ZIO-idiomatic wrapper around jnats' `ServiceMessage`. It provides:
 * - Access to the request payload, subject, and headers
 * - Typed decoding via [[NatsCodec]]
 * - Typed response methods
 * - Standard error responses with NATS service error headers
 *
 * ==Example handler==
 * {{{
 * val handler: ServiceRequest => IO[NatsError, Unit] = { req =>
 *   for {
 *     query  <- req.decode[UserQuery]
 *     result <- lookupUser(query)
 *     _      <- req.respond(result)
 *   } yield ()
 * }
 * }}}
 */
final class ServiceRequest private[nats] (
  private val jMsg: JServiceMessage,
  private val conn: JConnection
) {

  /** The subject this request was sent to. */
  def subject: Subject

  /** The reply-to subject (used internally by respond). */
  def replyTo: Option[String]

  /** Whether the request has headers. */
  def hasHeaders: Boolean

  /** Request headers. */
  def headers: Headers

  /** Raw request payload as bytes. */
  def data: Chunk[Byte]

  /** Raw request payload as a UTF-8 string. */
  def dataAsString: String

  /**
   * Decode the request payload as `A` using the implicit [[NatsCodec]].
   *
   * Fails with [[NatsError.DecodingError]] if decoding fails.
   */
  def decode[A: NatsCodec]: IO[NatsError, A]

  /**
   * Encode `value` as `A` and send it as the response.
   *
   * This is a terminal operation — only one response can be sent per request.
   */
  def respond[A: NatsCodec](value: A): IO[NatsError, Unit]

  /**
   * Encode `value` as `A` and send it as the response with custom headers.
   */
  def respond[A: NatsCodec](value: A, headers: Headers): IO[NatsError, Unit]

  /**
   * Send a standard NATS service error response.
   *
   * Sets the `Nats-Service-Error` and `Nats-Service-Error-Code` headers
   * on the response so that clients can distinguish errors from successful
   * responses at the protocol level.
   *
   * @param text     Human-readable error description
   * @param code     Numeric error code (use HTTP-style codes by convention)
   */
  def respondError(text: String, code: Int): IO[NatsError, Unit]
}
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
   * ==Example: single endpoint==
   * {{{
   * val config = ServiceConfig("greeter", "1.0.0", description = Some("Greeting service"))
   *
   * val greet: ServiceRequest => IO[NatsError, Unit] = { req =>
   *   for {
   *     name <- req.decode[String]
   *     _    <- req.respond(s"Hello, $name!")
   *   } yield ()
   * }
   *
   * nats.service(config, ServiceEndpointConfig("greet") -> greet)
   * }}}
   *
   * ==Example: grouped endpoints==
   * {{{
   * val sort = ServiceGroup("sort")
   *
   * nats.service(
   *   ServiceConfig("sorter", "1.0.0"),
   *   ServiceEndpointConfig("ascending", group = Some(sort))  -> ascending,
   *   ServiceEndpointConfig("descending", group = Some(sort)) -> descending
   * )
   * }}}
   *
   * @return A [[NatsService]] handle for querying stats and identity.
   */
  def service(
    config: ServiceConfig,
    endpoints: (ServiceEndpointConfig, ServiceRequest => IO[NatsError, Unit])*
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
  endpoints: (ServiceEndpointConfig, ServiceRequest => IO[NatsError, Unit])*
): ZIO[Scope, NatsError, NatsService] =
  for {
    runtime <- ZIO.runtime[Any]
    jService <- buildAndStart(config, endpoints, runtime)
  } yield new NatsServiceLive(jService, conn)

private def buildAndStart(
  config: ServiceConfig,
  endpoints: Seq[(ServiceEndpointConfig, ServiceRequest => IO[NatsError, Unit])],
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

      endpoints.foreach { case (epConfig, handler) =>
        builder.addServiceEndpoint(buildEndpoint(epConfig, handler, runtime))
      }

      val svc = builder.build()
      svc.startService() // returns CompletableFuture — service is ready immediately
      svc
    }.mapError(NatsError.fromThrowable)
  )(svc => ZIO.attempt(svc.stop()).ignoreLogged)

private def buildEndpoint(
  config: ServiceEndpointConfig,
  handler: ServiceRequest => IO[NatsError, Unit],
  runtime: Runtime[Any]
): JServiceEndpoint = {
  val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
    Unsafe.unsafeCompat { implicit unsafe =>
      runtime.unsafe.run(
        handler(new ServiceRequest(jMsg, conn)).catchAll { error =>
          ZIO.attempt(
            jMsg.respondStandardError(conn, error.message, 500)
          ).ignoreLogged
        }
      ).getOrThrowFiberFailure()
    }
  }

  val b = JServiceEndpoint.builder()
    .endpointName(config.name)
    .handler(jHandler)

  config.subject.foreach(b.endpointSubject)
  config.queueGroup match {
    case Some(Some(qg)) => b.endpointQueueGroup(qg)
    case Some(None)     => b.endpointQueueGroup(null) // disables queue group
    case None           => // use default "q"
  }
  config.group.foreach(g => b.group(buildGroup(g)))
  if (config.metadata.nonEmpty)
    b.endpointMetadata(config.metadata.asJava)

  b.build()
}

private def buildGroup(g: ServiceGroup): JGroup = {
  val jGroup = new JGroup(g.name)
  g.parent.foreach(p => buildGroup(p).appendGroup(jGroup))
  // Return the root group
  g.parent.map(buildGroup).getOrElse(jGroup)
}
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

### Minimal Service

```scala
import zio.*
import zio.nats.*

object MinimalServiceApp extends ZIOAppDefault {
  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          svc <- nats.service(
            ServiceConfig("echo", "1.0.0"),
            ServiceEndpointConfig("echo") -> { req =>
              req.respond(req.data) // echo raw bytes back
            }
          )
          _ <- Console.printLine(s"Service ${svc.name} running [${svc.id}]")
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Typed JSON Service

```scala
import zio.*
import zio.nats.*
import zio.schema.*

case class UserQuery(id: Int) derives Schema
case class UserResponse(id: Int, name: String, email: String) derives Schema

object UserServiceApp extends ZIOAppDefault {
  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  val lookup: ServiceRequest => IO[NatsError, Unit] = { req =>
    for {
      query <- req.decode[UserQuery]
      user   = UserResponse(query.id, "Alice", "alice@example.com")
      _     <- req.respond(user)
    } yield ()
  }

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          _ <- nats.service(
            ServiceConfig("user-service", "2.1.0", description = Some("User lookup")),
            ServiceEndpointConfig("lookup") -> lookup
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Multi-Endpoint with Groups

```scala
object SortServiceApp extends ZIOAppDefault {
  val sort = ServiceGroup("sort")

  val ascending: ServiceRequest => IO[NatsError, Unit] = { req =>
    for {
      data <- req.decode[String]
      _    <- req.respond(data.sorted.mkString)
    } yield ()
  }

  val descending: ServiceRequest => IO[NatsError, Unit] = { req =>
    for {
      data <- req.decode[String]
      _    <- req.respond(data.sorted.reverse.mkString)
    } yield ()
  }

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          _ <- nats.service(
            ServiceConfig("sorter", "1.0.0"),
            ServiceEndpointConfig("ascending",  group = Some(sort)) -> ascending,
            ServiceEndpointConfig("descending", group = Some(sort)) -> descending
          )
          _ <- ZIO.never
        } yield ()
      }
    }.provide(Nats.live, NatsConfig.default)
}
```

### Discovery Client

```scala
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
object ServiceIntegrationTest extends ZIOAppDefault {
  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  val run =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.scoped {
        for {
          // Start service
          svc <- nats.service(
            ServiceConfig("calculator", "1.0.0"),
            ServiceEndpointConfig("add") -> { req =>
              for {
                nums <- req.decode[List[Int]]
                _    <- req.respond(nums.sum.toString)
              } yield ()
            }
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

### Error Handling in Handlers

```scala
val handler: ServiceRequest => IO[NatsError, Unit] = { req =>
  for {
    query <- req.decode[UserQuery]
    user  <- lookupUser(query.id).some.orElseFail(
               NatsError.GeneralError(s"User ${query.id} not found", new Exception("not found"))
             )
    _     <- req.respond(user)
  } yield ()
  // If the handler fails with NatsError, the framework automatically sends
  // a 500 error response with the error message in Nats-Service-Error headers.
  // For explicit error codes, use req.respondError("Not found", 404) instead.
}
```

---

## File Inventory

| File | Action | Contents |
|---|---|---|
| `zio-nats/src/main/scala/zio/nats/service/ServiceConfig.scala` | **New** | `ServiceConfig`, `ServiceEndpointConfig`, `ServiceGroup` case classes with `toJava` |
| `zio-nats/src/main/scala/zio/nats/service/ServiceModels.scala` | **New** | `ServiceRequest`, `PingResponse`, `InfoResponse`, `StatsResponse`, `EndpointStats`, `EndpointInfo` |
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
| **Echo endpoint** | Send request, receive echoed response via `nats.request` |
| **Typed endpoint** | Decode JSON request, encode JSON response, verify round-trip |
| **Multi-endpoint** | Multiple endpoints on one service, each handles correctly |
| **Grouped endpoints** | ServiceGroup prefixes subjects correctly |
| **Error response** | Handler calls `respondError`, client receives error headers |
| **Unhandled error** | Handler fails with NatsError, automatic 500 response sent |
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
- **`queueGroup` triple option:** The `Option[Option[String]]` on `ServiceEndpointConfig` is admittedly ugly. Consider a sealed trait `QueueGroupPolicy` (`Default`, `Disabled`, `Custom(name)`) if it hurts readability.
- **Thread safety of `Unsafe.run`:** jnats dispatches handler callbacks on its own dispatcher threads. `runtime.unsafe.run` is safe to call from any thread. The handler ZIO runs on the ZIO runtime's executor, not the jnats dispatcher thread.
- **Handler concurrency:** Each request gets its own `Unsafe.run` invocation, so handlers run concurrently by default. No additional concurrency control is needed unless the user wants it (they can use `Semaphore`, `Ref`, etc. in their handler).
- **Custom stats data supplier:** jnats supports `Supplier<JsonValue>` for custom endpoint stats. Consider adding this later as an advanced option. Not needed for v1.
- **`CompletableFuture` from `startService()`:** The future completes when the service *stops*, not when it starts. The service is ready immediately after `build()` + `startService()`. Do not `await` this future during setup.
