package zio.nats

import io.nats.client.Connection as JConnection
import io.nats.client.api.ServerInfo
import zio.*
import zio.blocks.schema.Schema
import zio.nats.config.NatsConfig
import zio.nats.serialization.NatsSerializer
import zio.nats.subject.Subject
import zio.stream.*

/** Core NATS service: publish, subscribe, request-reply.
  *
  * Obtain via Nats.make(config) or Nats.live ZLayer.
  * All other services (JetStream, KeyValue, ObjectStore) are obtained from this service.
  */
trait Nats {

  // --- Raw publish (using Subject type) ---

  /** Publish raw bytes (fire-and-forget into the client outbound buffer). */
  def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, Unit]

  /** Publish raw bytes with NATS headers. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, Unit]

  /** Publish raw bytes with an explicit reply-to subject (for manual request/reply). */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    replyTo: Subject
  ): IO[NatsError, Unit]

  /** Publish raw bytes with headers and explicit reply-to. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    replyTo: Subject
  ): IO[NatsError, Unit]

  // --- Type-safe publish (serializes T to bytes using Schema) ---

  /** Publish a value of type T, serialized using the configured format. */
  def publish[T: Schema](subject: Subject, data: T): ZIO[NatsConfig, NatsError, Unit]

  /** Publish a value with NATS headers. */
  def publish[T: Schema](
    subject: Subject,
    data: T,
    headers: Map[String, List[String]]
  ): ZIO[NatsConfig, NatsError, Unit]

  // --- Request/Reply ---

  /** Send a request with raw bytes and await a single reply within the given timeout. */
  def request(
    subject: Subject,
    data: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): IO[NatsError, NatsMessage]

  /** Request with raw bytes and headers. */
  def request(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    timeout: Duration
  ): IO[NatsError, NatsMessage]

  // --- Subscribe ---

  /** Subscribe to a subject, returning a ZStream of raw messages.
    *
    * The underlying jnats Dispatcher is created when the stream is consumed
    * and closed automatically when the stream is interrupted or finishes.
    */
  def subscribe(subject: Subject): ZStream[Any, NatsError, NatsMessage]

  /** Subscribe to a subject with a queue group for load-balanced delivery. */
  def subscribe(subject: Subject, queue: Subject): ZStream[Any, NatsError, NatsMessage]

  // --- Utility methods ---

  /** Flush the outbound buffer to the server. */
  def flush(timeout: Duration = 1.second): IO[NatsError, Unit]

  /** Gracefully drain subscriptions and close the connection. */
  def drain(timeout: Duration = 30.seconds): IO[NatsError, Unit]

  /** Current connection status. */
  def status: UIO[JConnection.Status]

  /** Server info (available after connection). */
  def serverInfo: IO[NatsError, ServerInfo]

  /** Escape hatch: access the raw jnats Connection for advanced use. */
  def underlying: JConnection
}

object Nats {

  // --- Accessor methods for use in ZIO environment ---

  def publish(subject: Subject, data: Chunk[Byte]): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data))

  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data, headers))

  def publish(subject: Subject, data: Chunk[Byte], replyTo: Subject): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data, replyTo))

  def publish[T: Schema](subject: Subject, data: T): ZIO[Nats & NatsConfig, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish[T](subject, data))

  def publish[T: Schema](
    subject: Subject,
    data: T,
    headers: Map[String, List[String]]
  ): ZIO[Nats & NatsConfig, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish[T](subject, data, headers))

  def request(
    subject: Subject,
    data: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): ZIO[Nats, NatsError, NatsMessage] =
    ZIO.serviceWithZIO[Nats](_.request(subject, data, timeout))

  def subscribe(subject: Subject): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject))

  def subscribe(subject: Subject, queue: Subject): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject, queue))

  def flush(timeout: Duration = 1.second): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.flush(timeout))

  def status: URIO[Nats, JConnection.Status] =
    ZIO.serviceWithZIO[Nats](_.status)

  // --- Construction ---

  /** Create a managed NATS connection. The connection is closed when the Scope ends. */
  def make(config: NatsConfig): ZIO[Scope, NatsError, Nats] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(io.nats.client.Nats.connect(config.toOptions))
        .mapError(NatsError.fromThrowable)
    )(conn =>
      ZIO.attemptBlocking(conn.close()).ignoreLogged
    ).map(new NatsLive(_))

  /** ZLayer that reads NatsConfig from the environment. */
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

  // --- Type-safe extension methods ---

  def subscribeAs[T: Schema](subject: Subject): ZStream[Nats & NatsConfig, NatsError, T] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject)).mapZIO(decodeMessage[T])

  def subscribeAs[T: Schema](subject: Subject, queue: Subject): ZStream[Nats & NatsConfig, NatsError, T] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject, queue)).mapZIO(decodeMessage[T])

  private def decodeMessage[T: Schema](msg: NatsMessage): ZIO[NatsConfig, NatsError, T] =
    ZIO.serviceWithZIO[NatsConfig] { config =>
      ZIO.fromEither(NatsSerializer.decode[T](msg.data, config.format))
        .mapError(e => NatsError.SerializationError(e.getMessage, e))
    }
}

// ---------------------------------------------------------------------------
// Private implementation
// ---------------------------------------------------------------------------

private[nats] final class NatsLive(conn: JConnection) extends Nats {

  override def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, Unit] =
    ZIO.attempt(conn.publish(subject.value, data.toArray))
      .mapError(NatsError.fromThrowable)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, Unit] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
  }

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    replyTo: Subject
  ): IO[NatsError, Unit] =
    ZIO.attempt(conn.publish(subject.value, replyTo.value, data.toArray))
      .mapError(NatsError.fromThrowable)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    replyTo: Subject
  ): IO[NatsError, Unit] = {
    val msg = NatsMessage.toJava(subject.value, data, replyTo = Some(replyTo.value), headers = headers)
    ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
  }

  override def publish[T: Schema](subject: Subject, data: T): ZIO[NatsConfig, NatsError, Unit] =
    ZIO.serviceWithZIO[NatsConfig] { config =>
      ZIO.fromEither(NatsSerializer.encode(data, config.format).left.map(e => NatsError.SerializationError(e.getMessage, e)))
        .flatMap(b => publish(subject, b))
    }

  override def publish[T: Schema](
    subject: Subject,
    data: T,
    headers: Map[String, List[String]]
  ): ZIO[NatsConfig, NatsError, Unit] =
    ZIO.serviceWithZIO[NatsConfig] { config =>
      ZIO.fromEither(NatsSerializer.encode(data, config.format).left.map(e => NatsError.SerializationError(e.getMessage, e)))
        .flatMap(b => publish(subject, b, headers))
    }

  override def request(
    subject: Subject,
    data: Chunk[Byte],
    timeout: Duration
  ): IO[NatsError, NatsMessage] =
    ZIO.fromCompletionStage(
      conn.requestWithTimeout(subject.value, data.toArray, timeout.asJava)
    ).mapBoth(NatsError.fromThrowable, NatsMessage.fromJava)

  override def request(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    timeout: Duration
  ): IO[NatsError, NatsMessage] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO.fromCompletionStage(
      conn.requestWithTimeout(msg, timeout.asJava)
    ).mapBoth(NatsError.fromThrowable, NatsMessage.fromJava)
  }

  override def subscribe(subject: Subject): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, None)

  override def subscribe(subject: Subject, queue: Subject): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject.value, Some(queue.value))

  /** Internal: Dispatcher + Queue -> ZStream pattern.
    *
    * The jnats Dispatcher calls MessageHandler on its own thread.
    * We offer each message into an unbounded ZIO Queue which feeds the ZStream.
    * Both Queue and Dispatcher are cleaned up when the stream's Scope ends.
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
    ZIO.attemptBlocking(conn.flush(timeout.asJava))
      .mapError(NatsError.fromThrowable)

  override def drain(timeout: Duration): IO[NatsError, Unit] =
    ZIO.fromCompletionStage(conn.drain(timeout.asJava))
      .mapError(NatsError.fromThrowable)
      .unit

  override def status: UIO[JConnection.Status] =
    ZIO.succeed(conn.getStatus)

  override def serverInfo: IO[NatsError, ServerInfo] =
    ZIO.attempt(conn.getServerInfo).mapError(NatsError.fromThrowable)

  override def underlying: JConnection = conn
}
