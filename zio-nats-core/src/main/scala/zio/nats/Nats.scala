package zio.nats

import io.nats.client.{Connection => JConnection, ConnectionListener, ErrorListener, Options}
import io.nats.service.{Service => JService}
import zio._
import zio.nats.config.NatsConfig
import zio.nats.service.{
  BoundEndpoint,
  NatsService,
  NatsServiceLive,
  ServiceConfig,
  ServiceEndpoint => ServiceEndpointDescriptor
}
import zio.stream._

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters._

/**
 * Core NATS service: publish, subscribe, and request-reply.
 *
 * Obtain an instance via [[Nats.make]] or the [[Nats.live]] ZLayer. All other
 * services (JetStream, KeyValue, ObjectStore) are derived from this service.
 *
 * ==Setting up codecs==
 *
 * Typed methods require a [[NatsCodec]] for each type parameter, resolved at
 * compile time via Scala's `given`/`using` mechanism.
 *
 * {{{
 * // Install a default codec for all Schema-annotated types:
 * val codecs = NatsCodec.fromFormat(JsonFormat)
 * import codecs.derived
 *
 * // Override per type:
 * given auditCodec: NatsCodec[AuditEvent] =
 *   NatsCodec.fromFormat(BsonFormat).derived[AuditEvent]
 * }}}
 */
trait Nats {

  // -------------------------------------------------------------------------
  // Typed publish
  // -------------------------------------------------------------------------

  /**
   * Encode `value` with the given [[NatsCodec]] and publish to `subject`.
   *
   * Pass `Chunk[Byte]` to use the identity codec (raw bytes).
   *
   * @param params
   *   Optional [[PublishParams]] for headers and reply-to (defaults to
   *   [[PublishParams.empty]]).
   */
  def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams = PublishParams.empty
  ): IO[NatsError, Unit]

  // -------------------------------------------------------------------------
  // Typed request/reply
  // -------------------------------------------------------------------------

  /**
   * Encode `request` as `A`, send it, await the reply, then decode it as `B`.
   *
   * Returns an [[Envelope]] containing both the decoded response and the raw
   * [[NatsMessage]] (so headers and other metadata remain accessible).
   *
   * Pass `Chunk[Byte]` for `A` and/or `B` to use the identity codec (raw
   * bytes).
   *
   * Fails with [[NatsError.DecodingError]] if the reply cannot be decoded as
   * `B`. Fails with [[NatsError.Timeout]] if no reply is received within
   * `timeout`. Fails with [[NatsError.ServiceCallFailed]] if the responder is a
   * NATS Micro service endpoint that sent an error response (detected via the
   * standard `Nats-Service-Error` / `Nats-Service-Error-Code` headers).
   *
   * @param params
   *   Optional [[PublishParams]] for outbound headers (defaults to
   *   [[PublishParams.empty]]). The `replyTo` field of `params` is ignored —
   *   NATS manages the reply inbox automatically for request/reply.
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration,
    params: PublishParams = PublishParams.empty
  ): IO[NatsError, Envelope[B]]

  // -------------------------------------------------------------------------
  // Subscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribe and automatically decode each message payload.
   *
   * Returns a stream of [[Envelope]]s so callers have access to both the
   * decoded value and the raw [[NatsMessage]] (headers, subject, reply-to, raw
   * bytes).
   *
   * Pass an optional [[QueueGroup]] to enable load-balanced delivery: within a
   * queue group, each published message is delivered to exactly one subscriber.
   *
   * Pass `Chunk[Byte]` to use the identity codec and receive raw bytes:
   *
   * {{{
   * // Raw access — env.message exposes subject, headers, reply-to, and payload
   * nats.subscribe[Chunk[Byte]](Subject("events.>"))
   *   .tap(env => ZIO.debug(s"${env.message.subject}: ${env.message.dataAsString}"))
   *   .runDrain
   * }}}
   *
   * Decode failures are converted to [[NatsError.DecodingError]] and propagated
   * through the stream's error channel.
   */
  def subscribe[A: NatsCodec](
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Any, NatsError, Envelope[A]]

  /** Flush the outbound buffer to the server within `timeout`. */
  def flush(timeout: Duration = 1.second): IO[NatsError, Unit]

  /** Current connection status. Never fails. */
  def status: UIO[ConnectionStatus]

  /** Server information (available after a successful connection). */
  def serverInfo: IO[NatsError, NatsServerInfo]

  /**
   * Measure round-trip time to the server. Sends a PING and waits for PONG.
   * Fails with [[NatsError]] if the connection is closed or the ping times out.
   */
  def rtt: IO[NatsError, Duration]

  /** URL of the server this connection is currently using, if connected. */
  def connectedUrl: UIO[Option[String]]

  /**
   * Lifetime counters for this connection (messages in/out, reconnects, etc.).
   */
  def statistics: UIO[ConnectionStats]

  /** Number of messages waiting to be flushed to the server. */
  def outgoingPendingMessageCount: UIO[Long]

  /** Bytes waiting to be flushed to the server. */
  def outgoingPendingBytes: UIO[Long]

  /**
   * Typed service call: send `input` to the endpoint's subject and return the
   * decoded reply.
   *
   * Both domain errors and transport failures surface in the ZIO error channel:
   *
   *   - Succeeds with `out: Out` when the handler replies successfully.
   *   - Fails with `err: Err` when the handler returns a domain error.
   *   - Fails with [[NatsError.ServiceCallFailed]] for infrastructure errors
   *     (empty body with `Nats-Service-Error` header).
   *   - Fails with [[NatsError.Timeout]] if no reply arrives within `timeout`.
   *
   * `Err` may be a union type (e.g. `ValidationError | PaymentError`). The
   * server sets a `Nats-Service-Error-Type` header to the FQDN of the concrete
   * error class, and this method uses that header to dispatch decoding to the
   * correct member codec — built at compile time from the endpoint descriptor.
   *
   * Because the error channel is `NatsError | Err`, Scala 3 union types widen
   * automatically when composing multiple `requestService` calls:
   *
   * {{{
   * // IO[OrderError | PaymentError | InventoryError | NatsError, (OrderReply, Stock)]
   * nats.requestService(orderEp, orderReq, 5.seconds)
   *   .zipPar(nats.requestService(inventoryEp, inventoryReq, 5.seconds))
   * }}}
   *
   * The [[service.ServiceEndpoint]] descriptor is the authority on the subject,
   * input/output types, and codecs — it stays pure data and is not modified.
   *
   * ==Example: single error type==
   * {{{
   * val ep = ServiceEndpoint("lookup").in[UserQuery].out[UserResponse].failsWith[UserError]
   *
   * val user: IO[UserError | NatsError, UserResponse] =
   *   nats.requestService(ep, UserQuery("alice"), 5.seconds)
   * }}}
   *
   * ==Example: union error type==
   * {{{
   * val ep = ServiceEndpoint("place-order")
   *   .in[OrderRequest].out[OrderReply]
   *   .failsWith[ValidationError]
   *   .failsWith[PaymentError]
   *
   * val order: IO[ValidationError | PaymentError | NatsError, OrderReply] =
   *   nats.requestService(ep, OrderRequest(...), 5.seconds)
   * }}}
   *
   * For infallible endpoints (`Err = Nothing`) use [[request]] directly with
   * `endpoint.effectiveSubject`.
   *
   * @param params
   *   Optional [[PublishParams]] for outbound headers (defaults to
   *   [[PublishParams.empty]]). Headers arrive at the handler via
   *   [[service.ServiceRequest#headers]]. The `replyTo` field of `params` is
   *   ignored — NATS manages the reply inbox automatically.
   */
  def requestService[In, Err, Out](
    endpoint: ServiceEndpointDescriptor[In, Err, Out],
    input: In,
    timeout: Duration,
    params: PublishParams = PublishParams.empty
  ): IO[NatsError | Err, Out]

  /**
   * Create and start a NATS microservice.
   *
   * The service registers itself for discovery and begins handling requests
   * immediately. It is automatically stopped (with drain) when the enclosing
   * [[Scope]] ends.
   *
   * Endpoints are created by declaring a [[service.ServiceEndpoint]] and
   * binding a handler via `implement`:
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
   * @param config
   *   Service name, version, description, and metadata.
   * @param endpoints
   *   One or more [[service.BoundEndpoint]]s produced by
   *   [[service.ServiceEndpoint.implement]] or
   *   [[service.ServiceEndpoint.implementWithRequest]].
   * @return
   *   A [[service.NatsService]] handle for querying stats and identity.
   */
  def service(
    config: ServiceConfig,
    endpoints: BoundEndpoint*
  ): ZIO[Scope, NatsError, NatsService]

  /**
   * Escape hatch: access the raw jnats `Connection` for advanced or unsupported
   * use-cases not covered by the library's typed API.
   *
   * Returns a `UIO[JConnection]` so it composes naturally with other ZIO
   * effects. It is intentionally exposed as an escape hatch only. Prefer the
   * typed methods on [[Nats]], [[zio.nats.jetstream.JetStream]],
   * [[zio.nats.kv.KeyValue]], and [[zio.nats.objectstore.ObjectStore]] for all
   * production use. Calling jnats APIs directly can bypass the library's error
   * model and codec guarantees.
   */
  def underlying: UIO[JConnection]

  /**
   * Stream of connection lifecycle events for this connection.
   *
   * Emits a [[NatsEvent]] whenever the connection state changes — including
   * connects, disconnects, reconnects, lame-duck mode, errors, and exceptions.
   * Backed by an unbounded [[Hub]], so multiple concurrent subscribers are
   * supported and each receives all events independently.
   *
   * The stream never fails and lives as long as the [[Nats]] service itself.
   *
   * {{{
   * // Log all events until the connection closes:
   * ZIO.serviceWithStream[Nats](_.lifecycleEvents)
   *   .tap(e => Console.printLine(s"[nats] $e").orDie)
   *   .takeUntil(_ == NatsEvent.Closed)
   *   .runDrain
   *   .fork
   * }}}
   */
  def lifecycleEvents: ZStream[Any, Nothing, NatsEvent]
}

object Nats {

  // -------------------------------------------------------------------------
  // Layer construction
  // -------------------------------------------------------------------------

  /**
   * Create a managed NATS connection. The connection is closed when the
   * enclosing [[Scope]] ends.
   *
   * Connection lifecycle events are available via [[Nats#lifecycleEvents]] on
   * the returned service.
   */
  def make(config: NatsConfig): ZIO[Scope, NatsError, Nats] =
    for {
      hub   <- Hub.unbounded[NatsEvent]
      jQueue = new LinkedBlockingQueue[NatsEvent]()
      conn  <- connect(buildOptions(config, jQueue), config.drainTimeout)
      _     <- relayEvents(jQueue, hub)
    } yield new NatsLive(conn, hub)

  /** Wire jnats connection- and error-listeners to push events into `queue`. */
  private def buildOptions(config: NatsConfig, queue: LinkedBlockingQueue[NatsEvent]): Options =
    config.toOptionsBuilder.connectionListener { (conn: JConnection, eventType: ConnectionListener.Events) =>
      val url   = Option(conn.getConnectedUrl).getOrElse("unknown")
      val event = eventType match {
        case ConnectionListener.Events.CONNECTED          => NatsEvent.Connected(url)
        case ConnectionListener.Events.DISCONNECTED       => NatsEvent.Disconnected(url)
        case ConnectionListener.Events.RECONNECTED        => NatsEvent.Reconnected(url)
        case ConnectionListener.Events.CLOSED             => NatsEvent.Closed
        case ConnectionListener.Events.LAME_DUCK          => NatsEvent.LameDuckMode
        case ConnectionListener.Events.RESUBSCRIBED       => NatsEvent.Resubscribed(url)
        case ConnectionListener.Events.DISCOVERED_SERVERS => NatsEvent.ServersDiscovered
      }
      queue.put(event)
    }
      .errorListener(new ErrorListener {
        override def errorOccurred(conn: JConnection, error: String): Unit =
          queue.put(NatsEvent.Error(error))
        override def exceptionOccurred(conn: JConnection, exp: Exception): Unit =
          queue.put(NatsEvent.ExceptionOccurred(exp))
      })
      .build()

  /**
   * Acquire a jnats connection; release drains and closes it when the Scope
   * ends.
   */
  private def connect(options: Options, drainTimeout: Duration): ZIO[Scope, NatsError, JConnection] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(io.nats.client.Nats.connect(options)).mapError(NatsError.fromThrowable)
    )(conn => releaseConnection(conn, drainTimeout))

  private def releaseConnection(conn: JConnection, drainTimeout: Duration): UIO[Unit] = {
    val awaitDrain =
      ZIO.attemptBlockingInterrupt {
        conn.drain(drainTimeout.asJava).get(drainTimeout.toMillis, TimeUnit.MILLISECONDS)
      }.unit

    awaitDrain.catchAllCause(_ => ZIO.attemptBlocking(conn.close()).ignoreLogged)
  }

  /**
   * Drain `queue` into `hub` on a background fiber for the lifetime of the
   * Scope. The blocking `queue.take()` call is interrupted when the Scope ends.
   */
  private def relayEvents(
    queue: LinkedBlockingQueue[NatsEvent],
    hub: Hub[NatsEvent]
  ): ZIO[Scope, Nothing, Unit] =
    ZStream
      .repeatZIOOption(
        ZIO.attemptBlockingInterrupt(queue.take()).orElseFail(None)
      )
      .runForeach(hub.publish)
      .forkScoped
      .unit

  /** ZLayer that reads [[NatsConfig]] from the environment. */
  val live: ZLayer[NatsConfig, NatsError, Nats] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[NatsConfig]
        nats   <- make(config)
      } yield nats
    }

  /** Convenience layer: connect to localhost:4222 with defaults. */
  val default: ZLayer[Any, NatsError, Nats] =
    ZLayer.succeed(NatsConfig.default) >>> live
}

// ---------------------------------------------------------------------------
// Private live implementation
// ---------------------------------------------------------------------------

/** jnats-backed implementation of [[Nats]]. */
private[nats] final class NatsLive(conn: JConnection, hub: Hub[NatsEvent]) extends Nats {

  override def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams
  ): IO[NatsError, Unit] =
    ZIO
      .attempt(NatsCodec[A].encode(value))
      .mapError(e =>
        NatsError.SerializationError(s"Failed to encode message for subject '${subject.value}': ${e.toString}", e)
      )
      .flatMap { bytes =>
        if (params.headers.isEmpty && params.replyTo.isEmpty)
          ZIO.attempt(conn.publish(subject.value, bytes.toArray)).mapError(NatsError.fromThrowable)
        else {
          val msg = NatsMessage.toJava(
            subject = subject.value,
            data = bytes,
            replyTo = params.replyTo.map(_.value),
            headers = params.headers
          )
          ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
        }
      }

  override def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration,
    params: PublishParams
  ): IO[NatsError, Envelope[B]] =
    ZIO
      .attempt(NatsCodec[A].encode(request))
      .mapError(e =>
        NatsError.SerializationError(s"Failed to encode request for subject '${subject.value}': ${e.toString}", e)
      )
      .flatMap { bytes =>
        val send =
          if (params.headers.isEmpty)
            ZIO.attemptBlocking(Option(conn.request(subject.value, bytes.toArray, timeout.asJava)))
          else {
            val msg = NatsMessage.toJava(subject.value, bytes, headers = params.headers)
            ZIO.attemptBlocking(Option(conn.request(msg, timeout.asJava)))
          }
        send
          .mapError(NatsError.fromThrowable)
          .flatMap {
            case None =>
              ZIO.fail(NatsError.Timeout(s"No reply received for subject '${subject.value}' within $timeout"))
            case Some(jMsg) =>
              val msg = NatsMessage.fromJava(jMsg)
              extractServiceError(msg) match {
                case Some((errMsg, code)) => ZIO.fail(NatsError.ServiceCallFailed(errMsg, code))
                case None                 =>
                  ZIO
                    .fromEither(msg.decode[B])
                    .mapBoth(e => NatsError.DecodingError(e.message, e), Envelope(_, msg))
              }
          }
      }

  override def requestService[In, Err, Out](
    endpoint: ServiceEndpointDescriptor[In, Err, Out],
    input: In,
    timeout: Duration,
    params: PublishParams
  ): IO[NatsError | Err, Out] =
    ZIO
      .attempt(endpoint.inCodec.encode(input))
      .mapError(e =>
        NatsError.SerializationError(
          s"Failed to encode request for subject '${endpoint.effectiveSubject.value}': ${e.toString}",
          e
        )
      )
      .flatMap { bytes =>
        val send =
          if (params.headers.isEmpty)
            ZIO.attemptBlocking(
              Option(conn.request(endpoint.effectiveSubject.value, bytes.toArray, timeout.asJava))
            )
          else {
            val msg = NatsMessage.toJava(endpoint.effectiveSubject.value, bytes, headers = params.headers)
            ZIO.attemptBlocking(Option(conn.request(msg, timeout.asJava)))
          }
        send
          .mapError(NatsError.fromThrowable)
          .flatMap {
            case None =>
              ZIO.fail(
                NatsError.Timeout(
                  s"No reply received for subject '${endpoint.effectiveSubject.value}' within $timeout"
                )
              )
            case Some(jMsg) =>
              val msg = NatsMessage.fromJava(jMsg)
              decodeServiceReply(msg, endpoint.errCodec, endpoint.outCodec)
          }
      }

  override def subscribe[A: NatsCodec](
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Any, NatsError, Envelope[A]] =
    subscribeInternal(subject.value, queue.map(_.value)).mapZIO(decode[A])

  private def decode[A: NatsCodec](msg: NatsMessage): IO[NatsError, Envelope[A]] =
    ZIO
      .fromEither(msg.decode[A])
      .mapBoth(e => NatsError.DecodingError(e.message, e), Envelope(_, msg))

  /**
   * Extract NATS Micro error headers if present; returns `None` for success
   * replies.
   */
  private def extractServiceError(msg: NatsMessage): Option[(String, Int)] =
    msg.headers.get("Nats-Service-Error").headOption.map { errMsg =>
      val code = msg.headers.get("Nats-Service-Error-Code").headOption.flatMap(_.toIntOption).getOrElse(500)
      (errMsg, code)
    }

  /**
   * Decode a reply from a typed service call.
   *
   * On a service error header with a non-empty body, decodes the domain error
   * via `errCodec` and fails the effect. On an empty body, fails with an
   * infrastructure [[NatsError.ServiceCallFailed]]. On no error header, decodes
   * the success body via `outCodec`.
   */
  private def decodeServiceReply[Err, Out](
    msg: NatsMessage,
    errCodec: TypedErrorCodec[Err],
    outCodec: NatsCodec[Out]
  ): IO[NatsError | Err, Out] =
    extractServiceError(msg) match {
      case Some((errHeader, code)) =>
        if (msg.payload.nonEmpty)
          // Typed domain error — read the type discriminator header and
          // route to the correct member codec (supports union error types)
          val typeTag = msg.headers.get("Nats-Service-Error-Type").headOption.getOrElse("")
          ZIO
            .fromEither(errCodec.decode(msg.payload, typeTag))
            .mapError(e => NatsError.ServiceCallFailed(s"$errHeader (typed error decode failed: ${e.message})", code))
            .flatMap(err => ZIO.fail(err))
        else
          // Infrastructure error — empty body
          ZIO.fail(NatsError.ServiceCallFailed(errHeader, code))
      case None =>
        // Success — decode body as Out
        ZIO
          .fromEither(outCodec.decode(msg.payload))
          .mapError(e => NatsError.DecodingError(e.message, e))
    }

  /**
   * Internal: Dispatcher → ZStream pattern.
   *
   * A jnats Dispatcher delivers messages on its own thread into the ZStream via
   * the asyncScoped callback. The Dispatcher is closed when the stream's Scope
   * ends (interruption or normal completion).
   */
  private def subscribeInternal(
    subject: String,
    queue: Option[String]
  ): ZStream[Any, NatsError, NatsMessage] =
    ZStream.asyncScoped[Any, NatsError, NatsMessage] { emit =>
      ZIO.acquireRelease(
        ZIO.attempt {
          val handler: io.nats.client.MessageHandler = { msg =>
            emit(ZIO.succeed(Chunk.single(NatsMessage.fromJava(msg))))
          }
          val d = conn.createDispatcher(handler)
          queue match {
            case Some(q) => d.subscribe(subject, q)
            case None    => d.subscribe(subject)
          }
          d
        }.mapError(NatsError.fromThrowable)
      )(d => ZIO.attemptBlocking(conn.closeDispatcher(d)).ignoreLogged)
    }

  override def flush(timeout: Duration): IO[NatsError, Unit] =
    ZIO
      .attemptBlocking(conn.flush(timeout.asJava))
      .mapError(NatsError.fromThrowable)

  override def status: UIO[ConnectionStatus] =
    ZIO.succeed(ConnectionStatus.fromJava(conn.getStatus))

  override def serverInfo: IO[NatsError, NatsServerInfo] =
    ZIO
      .attempt(conn.getServerInfo)
      .mapBoth(NatsError.fromThrowable, NatsServerInfo.fromJava)

  override def rtt: IO[NatsError, Duration] =
    ZIO.attemptBlocking(conn.RTT()).mapBoth(NatsError.fromThrowable, d => Duration.fromJava(d))

  override def connectedUrl: UIO[Option[String]] =
    ZIO.succeed(Option(conn.getConnectedUrl))

  override def statistics: UIO[ConnectionStats] =
    ZIO.succeed(ConnectionStats.fromJava(conn.getStatistics))

  override def outgoingPendingMessageCount: UIO[Long] =
    ZIO.succeed(conn.outgoingPendingMessageCount())

  override def outgoingPendingBytes: UIO[Long] =
    ZIO.succeed(conn.outgoingPendingBytes())

  override def service(
    config: ServiceConfig,
    endpoints: BoundEndpoint*
  ): ZIO[Scope, NatsError, NatsService] =
    for {
      runtime  <- ZIO.runtime[Any]
      jService <- buildAndStartService(config, endpoints.toSeq, runtime)
    } yield new NatsServiceLive(jService)

  private def buildAndStartService(
    config: ServiceConfig,
    endpoints: Seq[BoundEndpoint],
    runtime: Runtime[Any]
  ): ZIO[Scope, NatsError, JService] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val builder = JService
          .builder()
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
        svc.startService() // CompletableFuture completes when service stops — do not await
        svc
      }
        .mapError(NatsError.fromThrowable)
    )(svc => ZIO.attempt(svc.stop()).ignoreLogged)

  override def underlying: UIO[JConnection] = ZIO.succeed(conn)

  override def lifecycleEvents: ZStream[Any, Nothing, NatsEvent] = ZStream.fromHub(hub)
}
