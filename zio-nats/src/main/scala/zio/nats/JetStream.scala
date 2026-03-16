package zio.nats

import io.nats.client.{ConsumerContext as JConsumerContext, JetStream as JJetStream, StreamContext as JStreamContext}
import zio.*
import zio.blocks.schema.Schema
import zio.nats.config.NatsConfig
import zio.nats.serialization.NatsSerializer
import zio.nats.subject.Subject

/** JetStream publishing service. */
trait JetStream {

  /** Publish to a JetStream subject (synchronous server ack). */
  def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, PublishAck]

  /** Publish with NATS headers. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, PublishAck]

  /** Publish with PublishOptions (message ID, expected-sequence, etc.). */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /** Publish with headers and options. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /** Publish a value of type T, serialized using the configured format. */
  def publish[T: Schema](subject: Subject, data: T): ZIO[NatsConfig, NatsError, PublishAck]

  def publishAsync(subject: Subject, data: Chunk[Byte]): IO[NatsError, Task[PublishAck]]

  def publishAsync(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, Task[PublishAck]]

  /** Get a Consumer handle for a named durable consumer. */
  def consumer(streamName: String, consumerName: String): IO[NatsError, Consumer]
}

object JetStream {

  def publish(subject: Subject, data: Chunk[Byte]): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data))

  def publish[T: Schema](subject: Subject, data: T): ZIO[JetStream & NatsConfig, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data))

  def publishAsync(
    subject: Subject,
    data: Chunk[Byte]
  ): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data))

  def consumer(
    streamName: String,
    consumerName: String
  ): ZIO[JetStream, NatsError, Consumer] =
    ZIO.serviceWithZIO[JetStream](_.consumer(streamName, consumerName))

  /** Create from a Nats connection. */
  val live: ZLayer[Nats, NatsError, JetStream] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        js   <- ZIO.attempt(nats.underlying.jetStream())
                  .mapError(NatsError.fromThrowable)
      } yield new JetStreamLive(js)
    }
}

private[nats] final class JetStreamLive(js: JJetStream) extends JetStream {

  override def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, PublishAck] =
    ZIO.attemptBlocking(js.publish(subject.value, data.toArray))
      .mapError(NatsError.fromThrowable)
      .map(PublishAck.fromJava)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO.attemptBlocking(js.publish(msg))
      .mapError(NatsError.fromThrowable)
      .map(PublishAck.fromJava)
  }

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck] =
    ZIO.attemptBlocking(js.publish(subject.value, data.toArray, options.toJava))
      .mapError(NatsError.fromThrowable)
      .map(PublishAck.fromJava)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    options: PublishOptions
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO.attemptBlocking(js.publish(msg, options.toJava))
      .mapError(NatsError.fromThrowable)
      .map(PublishAck.fromJava)
  }

  override def publish[T: Schema](subject: Subject, data: T): ZIO[NatsConfig, NatsError, PublishAck] =
    ZIO.serviceWithZIO[NatsConfig] { config =>
      val bytes = NatsSerializer.encode(data, config.format).left.map(e => NatsError.SerializationError(e.getMessage, e))
      ZIO.fromEither(bytes).flatMap { b =>
        ZIO.attemptBlocking(js.publish(subject.value, b.toArray))
          .mapError(NatsError.fromThrowable)
          .map(PublishAck.fromJava)
      }
    }

  override def publishAsync(
    subject: Subject,
    data: Chunk[Byte]
  ): IO[NatsError, Task[PublishAck]] =
    ZIO.attempt {
      val future = js.publishAsync(subject.value, data.toArray)
      ZIO.fromCompletionStage(future).map(PublishAck.fromJava)
    }.mapError(NatsError.fromThrowable)

  override def publishAsync(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, Task[PublishAck]] =
    ZIO.attempt {
      val future = js.publishAsync(subject.value, data.toArray, options.toJava)
      ZIO.fromCompletionStage(future).map(PublishAck.fromJava)
    }.mapError(NatsError.fromThrowable)

  override def consumer(streamName: String, consumerName: String): IO[NatsError, Consumer] =
    ZIO.attemptBlocking(js.getConsumerContext(streamName, consumerName))
      .mapError(NatsError.fromThrowable)
      .map(ctx => new ConsumerLive(streamName, consumerName, ctx))

  private[nats] def streamContext(streamName: String): IO[NatsError, JStreamContext] =
    ZIO.attemptBlocking(js.getStreamContext(streamName)).mapError(NatsError.fromThrowable)

  private[nats] def consumerContext(
    streamName: String,
    consumerName: String
  ): IO[NatsError, JConsumerContext] =
    ZIO.attemptBlocking(js.getConsumerContext(streamName, consumerName))
      .mapError(NatsError.fromThrowable)
}
