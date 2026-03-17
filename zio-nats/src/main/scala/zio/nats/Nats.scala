package zio.nats

import io.nats.client.{Connection => JConnection}
import zio._
import zio.nats.config.NatsConfig
import zio.stream._

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
   * Use the [[Nats]] companion accessor for a version with a default `timeout`:
   * {{{
   *   Nats.request[A, B](subject, value)   // 2-second timeout is used
   * }}}
   *
   * Pass `Chunk[Byte]` for `A` and/or `B` to use the identity codec (raw bytes).
   *
   * Decode failures are surfaced as [[NatsError.DecodingError]].
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): IO[NatsError, Envelope[B]]

  // -------------------------------------------------------------------------
  // Subscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribe and automatically decode each message payload.
   *
   * Returns a stream of [[Envelope]]s so callers have access to both the
   * decoded value and the raw [[NatsMessage]] (headers, subject, reply-to).
   *
   * Pass an optional [[QueueGroup]] to enable load-balanced delivery: within a
   * queue group, each published message is delivered to exactly one subscriber.
   *
   * Pass `Chunk[Byte]` to use the identity codec (raw bytes).
   *
   * Decode failures are converted to [[NatsError.DecodingError]] and propagated
   * through the stream's error channel.
   */
  def subscribe[A: NatsCodec](
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Any, NatsError, Envelope[A]]

  // -------------------------------------------------------------------------
  // Utility
  // -------------------------------------------------------------------------

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

  /** Lifetime counters for this connection (messages in/out, reconnects, etc.). */
  def statistics: UIO[ConnectionStats]

  /** Number of messages waiting to be flushed to the server. */
  def outgoingPendingMessageCount: UIO[Long]

  /** Bytes waiting to be flushed to the server. */
  def outgoingPendingBytes: UIO[Long]

  /**
   * Escape hatch: access the raw jnats Connection for advanced or unsupported
   * use-cases.
   */
  def underlying: JConnection

  /**
   * Subscribe to `subject` and receive raw [[NatsMessage]]s without decoding.
   *
   * Pass an optional [[QueueGroup]] to enable load-balanced delivery.
   *
   * Use when you need the full [[NatsMessage]] (subject, headers, reply-to,
   * raw bytes) and do not need typed decoding. For typed access prefer
   * [[subscribe]][A] which wraps each message in an [[Envelope]].
   */
  def subscribeRaw(
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Any, NatsError, NatsMessage]
}

object Nats {

  // -------------------------------------------------------------------------
  // Accessor methods for use in the ZIO environment
  // -------------------------------------------------------------------------

  def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams = PublishParams.empty
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish[A](subject, value, params))

  /**
   * Typed request with explicit timeout (mirrors the trait method).
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): ZIO[Nats, NatsError, Envelope[B]] =
    ZIO.serviceWithZIO[Nats](_.request[A, B](subject, request, timeout))

  /**
   * Typed request — convenience overload with a 2-second default timeout.
   *
   * {{{
   *   Nats.request[UserQuery, UserResponse](subject, query)
   * }}}
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A
  ): ZIO[Nats, NatsError, Envelope[B]] =
    ZIO.serviceWithZIO[Nats](_.request[A, B](subject, request, 2.seconds))

  def subscribeRaw(
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribeRaw(subject, queue))

  def subscribe[A: NatsCodec](
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Nats, NatsError, Envelope[A]] =
    ZStream.serviceWithStream[Nats](_.subscribe[A](subject, queue))

  def flush(timeout: Duration = 1.second): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.flush(timeout))

  def status: URIO[Nats, ConnectionStatus] =
    ZIO.serviceWithZIO[Nats](_.status)

  def rtt: ZIO[Nats, NatsError, Duration] =
    ZIO.serviceWithZIO[Nats](_.rtt)

  def connectedUrl: URIO[Nats, Option[String]] =
    ZIO.serviceWithZIO[Nats](_.connectedUrl)

  def statistics: URIO[Nats, ConnectionStats] =
    ZIO.serviceWithZIO[Nats](_.statistics)

  def outgoingPendingMessageCount: URIO[Nats, Long] =
    ZIO.serviceWithZIO[Nats](_.outgoingPendingMessageCount)

  def outgoingPendingBytes: URIO[Nats, Long] =
    ZIO.serviceWithZIO[Nats](_.outgoingPendingBytes)

  // -------------------------------------------------------------------------
  // Layer construction
  // -------------------------------------------------------------------------

  /**
   * Create a managed NATS connection. The connection is closed when the
   * enclosing [[Scope]] ends.
   */
  def make(config: NatsConfig): ZIO[Scope, NatsError, Nats] =
    ZIO
      .acquireRelease(
        ZIO
          .attemptBlocking(io.nats.client.Nats.connect(config.toOptions))
          .mapError(NatsError.fromThrowable)
      )(conn => ZIO.attemptBlocking(conn.close()).ignoreLogged)
      .map(new NatsLive(_))

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

private[nats] final class NatsLive(conn: JConnection) extends Nats {

  override def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams
  ): IO[NatsError, Unit] = {
    val bytes = NatsCodec[A].encode(value)
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
  ): IO[NatsError, Envelope[B]] = {
    val bytes = NatsCodec[A].encode(request)
    ZIO
      .fromCompletionStage(conn.requestWithTimeout(subject.value, bytes.toArray, timeout.asJava))
      .mapError(NatsError.fromThrowable)
      .flatMap { jMsg =>
        val msg = NatsMessage.fromJava(jMsg)
        ZIO
          .fromEither(msg.decode[B])
          .mapBoth(e => NatsError.DecodingError(e.message, e), Envelope(_, msg))
      }
  }

  override def subscribeRaw(
    subject: Subject,
    queue: Option[QueueGroup] = None
  ): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, queue.map(_.value))

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

  override def underlying: JConnection = conn
}
