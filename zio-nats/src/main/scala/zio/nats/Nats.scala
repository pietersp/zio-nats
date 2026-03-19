package zio.nats

import io.nats.client.{Connection => JConnection, ConnectionListener, ErrorListener, Options}
import io.nats.service.{Service => JService}
import zio._
import zio.nats.config.NatsConfig
import zio.nats.service.{BoundEndpoint, NatsService, NatsServiceLive, ServiceConfig}
import zio.stream._

import java.util.concurrent.LinkedBlockingQueue
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
   * `timeout`.
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): IO[NatsError, Envelope[B]]

  /**
   * Typed request — convenience overload with a 2-second default timeout.
   *
   * {{{
   *   nats.request[UserQuery, UserResponse](subject, query)
   * }}}
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A
  ): IO[NatsError, Envelope[B]] = this.request[A, B](subject, request, 2.seconds)

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

  /** Gracefully drain all subscriptions and close the connection. */
  def drain(timeout: Duration = 30.seconds): IO[NatsError, Unit]

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
   * Escape hatch: access the raw jnats Connection for advanced or unsupported
   * use-cases.
   */
  def underlying: JConnection

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
      conn  <- connect(buildOptions(config, jQueue))
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

  /** Acquire a jnats connection; release closes it when the Scope ends. */
  private def connect(options: Options): ZIO[Scope, NatsError, JConnection] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(io.nats.client.Nats.connect(options)).mapError(NatsError.fromThrowable)
    )(conn => ZIO.attemptBlocking(conn.close()).ignoreLogged)

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
    timeout: Duration
  ): IO[NatsError, Envelope[B]] =
    ZIO
      .attempt(NatsCodec[A].encode(request))
      .mapError(e =>
        NatsError.SerializationError(s"Failed to encode request for subject '${subject.value}': ${e.toString}", e)
      )
      .flatMap { bytes =>
        ZIO
          .attemptBlocking(Option(conn.request(subject.value, bytes.toArray, timeout.asJava)))
          .mapError(NatsError.fromThrowable)
          .flatMap {
            case None =>
              ZIO.fail(NatsError.Timeout(s"No reply received for subject '${subject.value}' within $timeout"))
            case Some(jMsg) =>
              val msg = NatsMessage.fromJava(jMsg)
              ZIO
                .fromEither(msg.decode[B])
                .mapBoth(e => NatsError.DecodingError(e.message, e), Envelope(_, msg))
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
      )(d => ZIO.attempt(conn.closeDispatcher(d)).ignoreLogged)
    }

  override def flush(timeout: Duration): IO[NatsError, Unit] =
    ZIO
      .attemptBlocking(conn.flush(timeout.asJava))
      .mapError(NatsError.fromThrowable)

  override def drain(timeout: Duration): IO[NatsError, Unit] =
    ZIO
      .fromCompletionStage(conn.drain(timeout.asJava))
      .mapError(NatsError.fromThrowable)
      .unit

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

  override def underlying: JConnection = conn

  override def lifecycleEvents: ZStream[Any, Nothing, NatsEvent] = ZStream.fromHub(hub)
}
