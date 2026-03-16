package zio.nats

import io.nats.client.{Connection => JConnection}
import zio._
import zio.nats.config.NatsConfig
import zio.nats.subject.Subject
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
 * compile time via Scala's implicit/given mechanism.
 *
 * {{{
 * // Install a default codec for all Schema-annotated types:
 * val codecs = NatsCodec.fromFormat(JsonFormat)
 * import codecs.derived          // Scala 3
 * // import codecs._             // Scala 2.13
 *
 * // Override per type:
 * implicit val auditCodec: NatsCodec[AuditEvent] =
 *   NatsCodec.fromFormat(BsonFormat).derived[AuditEvent]
 * }}}
 */
trait Nats {

  // -------------------------------------------------------------------------
  // Raw publish
  // -------------------------------------------------------------------------

  /**
   * Publish raw bytes to `subject`.
   *
   * @param params
   *   Optional [[PublishParams]] for headers and reply-to (defaults to
   *   [[PublishParams.empty]]).
   */
  def publish(
    subject: Subject,
    payload: Chunk[Byte],
    params: PublishParams = PublishParams.empty
  ): IO[NatsError, Unit]

  // -------------------------------------------------------------------------
  // Typed publish
  // -------------------------------------------------------------------------

  /**
   * Encode `value` with the implicit [[NatsCodec]] and publish to `subject`.
   *
   * Use the [[Nats]] companion accessor for a version with a default `params`:
   * {{{
   *   Nats.publish(subject, value)   // PublishParams.empty is used
   * }}}
   *
   * @param params
   *   [[PublishParams]] for headers and reply-to.
   */
  def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams
  ): IO[NatsError, Unit]

  // -------------------------------------------------------------------------
  // Raw request/reply
  // -------------------------------------------------------------------------

  /**
   * Send a request with raw bytes and await a single reply within `timeout`.
   *
   * Uses the NATS built-in request-reply mechanism (auto-generated reply-to
   * inbox).
   */
  def request(
    subject: Subject,
    payload: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): IO[NatsError, NatsMessage]

  // -------------------------------------------------------------------------
  // Typed request/reply
  // -------------------------------------------------------------------------

  /**
   * Encode `request` as `A`, send it, await the reply, then decode it as `B`.
   *
   * Use the [[Nats]] companion accessor for a version with a default `timeout`:
   * {{{
   *   Nats.request[A, B](subject, value)   // 2-second timeout is used
   * }}}
   *
   * Decode failures are surfaced as [[NatsError.DecodingError]].
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): IO[NatsError, B]

  // -------------------------------------------------------------------------
  // Subscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribe to `subject`, returning a [[ZStream]] of raw [[NatsMessage]]s.
   *
   * The underlying jnats Dispatcher is created when the stream starts and
   * closed automatically on interruption or completion.
   */
  def subscribe(subject: Subject): ZStream[Any, NatsError, NatsMessage]

  /**
   * Subscribe with a [[QueueGroup]] for load-balanced delivery.
   *
   * Within a queue group, each published message is delivered to exactly one
   * subscriber in the group.
   */
  def subscribe(
    subject: Subject,
    queue: QueueGroup
  ): ZStream[Any, NatsError, NatsMessage]

  /**
   * Subscribe and automatically decode each message payload.
   *
   * Decode failures are converted to [[NatsError.DecodingError]] and propagated
   * through the stream's error channel.
   */
  def subscribe[A: NatsCodec](subject: Subject): ZStream[Any, NatsError, A]

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
   * Escape hatch: access the raw jnats Connection for advanced or unsupported
   * use-cases.
   */
  def underlying: JConnection

  /**
   * Subscribe to `subject` and receive raw [[NatsMessage]]s.
   *
   * Unlike the overloaded `subscribe(subject)`, this method is unambiguous at
   * every call site because it has a unique name. Prefer this over
   * `subscribe(subject)` when holding a `Nats` instance directly.
   *
   * Equivalent to `subscribe(subject)` — the underlying jnats Dispatcher is
   * created when the stream starts and closed on interruption or completion.
   */
  def subscribeRaw(subject: Subject): ZStream[Any, NatsError, NatsMessage]
}

object Nats {

  // -------------------------------------------------------------------------
  // Accessor methods for use in the ZIO environment
  // -------------------------------------------------------------------------

  def publish(
    subject: Subject,
    payload: Chunk[Byte],
    params: PublishParams = PublishParams.empty
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, payload, params))

  /**
   * Typed publish with explicit params (mirrors the trait method). Prefer the
   * 2-arg overload below for the common case (no custom params).
   */
  def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish[A](subject, value, params))

  /**
   * Typed publish — convenience overload with [[PublishParams.empty]].
   *
   * {{{
   *   Nats.publish(subject, UserCreated("1"))
   * }}}
   */
  def publish[A: NatsCodec](
    subject: Subject,
    value: A
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish[A](subject, value, PublishParams.empty))

  def request(
    subject: Subject,
    payload: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): ZIO[Nats, NatsError, NatsMessage] =
    ZIO.serviceWithZIO[Nats](_.request(subject, payload, timeout))

  /**
   * Typed request with explicit timeout (mirrors the trait method).
   */
  def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): ZIO[Nats, NatsError, B] =
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
  ): ZIO[Nats, NatsError, B] =
    ZIO.serviceWithZIO[Nats](_.request[A, B](subject, request, 2.seconds))

  /**
   * Subscribe to `subject` and receive raw [[NatsMessage]]s.
   *
   * Uses a unique name to avoid the typed/raw overload ambiguity that arises
   * with `subscribe(subject)`. Prefer this over `subscribe(subject)` when
   * calling through the environment accessor.
   */
  def subscribeRaw(subject: Subject): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribeRaw(subject))

  def subscribe(
    subject: Subject,
    queue: QueueGroup
  ): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject, queue))

  def subscribe[A: NatsCodec](subject: Subject): ZStream[Nats, NatsError, A] =
    ZStream.serviceWithStream[Nats](_.subscribe[A](subject))

  def flush(timeout: Duration = 1.second): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.flush(timeout))

  def status: URIO[Nats, ConnectionStatus] =
    ZIO.serviceWithZIO[Nats](_.status)

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

  override def publish(
    subject: Subject,
    payload: Chunk[Byte],
    params: PublishParams
  ): IO[NatsError, Unit] =
    if (params.headers.isEmpty && params.replyTo.isEmpty)
      ZIO.attempt(conn.publish(subject.value, payload.toArray)).mapError(NatsError.fromThrowable)
    else {
      val msg = NatsMessage.toJava(
        subject = subject.value,
        data = payload,
        replyTo = params.replyTo.map(_.value),
        headers = params.headers
      )
      ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
    }

  override def publish[A: NatsCodec](
    subject: Subject,
    value: A,
    params: PublishParams
  ): IO[NatsError, Unit] = {
    val bytes = NatsCodec[A].encode(value)
    publish(subject, bytes, params)
  }

  override def request(
    subject: Subject,
    payload: Chunk[Byte],
    timeout: Duration
  ): IO[NatsError, NatsMessage] =
    ZIO
      .fromCompletionStage(
        conn.requestWithTimeout(subject.value, payload.toArray, timeout.asJava)
      )
      .mapBoth(NatsError.fromThrowable, NatsMessage.fromJava)

  override def request[A: NatsCodec, B: NatsCodec](
    subject: Subject,
    request: A,
    timeout: Duration
  ): IO[NatsError, B] = {
    val bytes = NatsCodec[A].encode(request)
    this
      .request(subject, bytes, timeout)
      .flatMap { msg =>
        ZIO
          .fromEither(msg.decode[B])
          .mapError(e => NatsError.DecodingError(e.message, e))
      }
  }

  override def subscribe(subject: Subject): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, None)

  override def subscribeRaw(subject: Subject): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, None)

  override def subscribe(
    subject: Subject,
    queue: QueueGroup
  ): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, Some(queue.value))

  override def subscribe[A: NatsCodec](subject: Subject): ZStream[Any, NatsError, A] =
    subscribeInternal(subject.value, None).mapZIO { msg =>
      ZIO
        .fromEither(msg.decode[A])
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

  override def underlying: JConnection = conn
}
