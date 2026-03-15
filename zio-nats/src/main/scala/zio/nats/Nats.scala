package zio.nats

import io.nats.client.{Connection => JConnection}
import io.nats.client.api.ServerInfo
import zio._
import zio.stream._
import zio.nats.config.NatsConfig

/** Core NATS service: publish, subscribe, request-reply.
  *
  * Obtain via Nats.make(config) or Nats.live ZLayer.
  * All other services (JetStream, KeyValue, ObjectStore) are obtained from this service.
  */
trait Nats {

  /** Publish a message (fire-and-forget into the client outbound buffer). */
  def publish(subject: String, data: Chunk[Byte]): IO[NatsError, Unit]

  /** Publish with NATS headers. */
  def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, Unit]

  /** Publish with an explicit reply-to subject (for manual request/reply). */
  def publish(
    subject: String,
    data: Chunk[Byte],
    replyTo: String
  ): IO[NatsError, Unit]

  /** Publish with headers and explicit reply-to. */
  def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    replyTo: String
  ): IO[NatsError, Unit]

  /** Send a request and await a single reply within the given timeout. */
  def request(
    subject: String,
    data: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): IO[NatsError, NatsMessage]

  /** Request with headers. */
  def request(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    timeout: Duration
  ): IO[NatsError, NatsMessage]

  /** Subscribe to a subject, returning a ZStream of messages.
    *
    * The underlying jnats Dispatcher is created when the stream is consumed
    * and closed automatically when the stream is interrupted or finishes.
    */
  def subscribe(subject: String): ZStream[Any, NatsError, NatsMessage]

  /** Subscribe to a subject with a queue group for load-balanced delivery. */
  def subscribe(subject: String, queue: String): ZStream[Any, NatsError, NatsMessage]

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

  def publish(subject: String, data: Chunk[Byte]): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data))

  def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data, headers))

  def publish(subject: String, data: Chunk[Byte], replyTo: String): ZIO[Nats, NatsError, Unit] =
    ZIO.serviceWithZIO[Nats](_.publish(subject, data, replyTo))

  def request(
    subject: String,
    data: Chunk[Byte],
    timeout: Duration = 2.seconds
  ): ZIO[Nats, NatsError, NatsMessage] =
    ZIO.serviceWithZIO[Nats](_.request(subject, data, timeout))

  def subscribe(subject: String): ZStream[Nats, NatsError, NatsMessage] =
    ZStream.serviceWithStream[Nats](_.subscribe(subject))

  def subscribe(subject: String, queue: String): ZStream[Nats, NatsError, NatsMessage] =
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
}

// ---------------------------------------------------------------------------
// Private implementation
// ---------------------------------------------------------------------------

private[nats] final class NatsLive(conn: JConnection) extends Nats {

  override def publish(subject: String, data: Chunk[Byte]): IO[NatsError, Unit] =
    ZIO.attempt(conn.publish(subject, data.toArray))
      .mapError(NatsError.fromThrowable)

  override def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, Unit] = {
    val msg = NatsMessage.toJava(subject, data, headers = headers)
    ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
  }

  override def publish(
    subject: String,
    data: Chunk[Byte],
    replyTo: String
  ): IO[NatsError, Unit] =
    ZIO.attempt(conn.publish(subject, replyTo, data.toArray))
      .mapError(NatsError.fromThrowable)

  override def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    replyTo: String
  ): IO[NatsError, Unit] = {
    val msg = NatsMessage.toJava(subject, data, replyTo = Some(replyTo), headers = headers)
    ZIO.attempt(conn.publish(msg)).mapError(NatsError.fromThrowable)
  }

  override def request(
    subject: String,
    data: Chunk[Byte],
    timeout: Duration
  ): IO[NatsError, NatsMessage] =
    ZIO.fromCompletionStage(
      conn.requestWithTimeout(subject, data.toArray, timeout.asJava)
    ).mapBoth(NatsError.fromThrowable, NatsMessage.fromJava)

  override def request(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    timeout: Duration
  ): IO[NatsError, NatsMessage] = {
    val msg = NatsMessage.toJava(subject, data, headers = headers)
    ZIO.fromCompletionStage(
      conn.requestWithTimeout(msg, timeout.asJava)
    ).mapBoth(NatsError.fromThrowable, NatsMessage.fromJava)
  }

  override def subscribe(subject: String): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject, None)

  override def subscribe(subject: String, queue: String): ZStream[Any, NatsError, NatsMessage] =
    subscribeInternal(subject, Some(queue))

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
    ZStream.unwrapScoped {
      for {
        msgQueue <- ZIO.acquireRelease(
                      Queue.unbounded[NatsMessage]
                    )(_.shutdown)
        _ <- ZIO.acquireRelease(
               ZIO.attempt {
                 val handler: io.nats.client.MessageHandler = { msg =>
                   zio.Unsafe.unsafe { implicit u =>
                     zio.Runtime.default.unsafe
                       .run(msgQueue.offer(NatsMessage.fromJava(msg)))
                       .getOrThrowFiberFailure()
                   }
                 }
                 val d = conn.createDispatcher(handler)
                 queue match {
                   case Some(q) => d.subscribe(subject, q)
                   case None    => d.subscribe(subject)
                 }
                 d
               }.mapError(NatsError.fromThrowable)
             )(d => ZIO.attempt(conn.closeDispatcher(d)).ignoreLogged)
      } yield ZStream.fromQueue(msgQueue)
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

