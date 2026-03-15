package zio.nats

import io.nats.client.{JetStream => JJetStream}
import io.nats.client.api.PublishAck
import io.nats.client.{PublishOptions, ConsumerContext => JConsumerContext, StreamContext => JStreamContext}
import zio._

/** JetStream publishing service and context access. */
trait JetStream {

  /** Publish to a JetStream subject (synchronous server ack). */
  def publish(subject: String, data: Chunk[Byte]): IO[NatsError, PublishAck]

  /** Publish with NATS headers. */
  def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, PublishAck]

  /** Publish with PublishOptions (message ID, expected-sequence, etc.). */
  def publish(
    subject: String,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /** Publish with headers and options. */
  def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /** Async publish - returns immediately with a Task that resolves when ack arrives. */
  def publishAsync(subject: String, data: Chunk[Byte]): IO[NatsError, Task[PublishAck]]

  /** Async publish with options. */
  def publishAsync(
    subject: String,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, Task[PublishAck]]

  /** Get a StreamContext for the simplified consumer API. */
  def streamContext(streamName: String): IO[NatsError, JStreamContext]

  /** Get a ConsumerContext to consume from a named durable consumer. */
  def consumerContext(streamName: String, consumerName: String): IO[NatsError, JConsumerContext]
}

object JetStream {

  def publish(subject: String, data: Chunk[Byte]): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data))

  def publishAsync(
    subject: String,
    data: Chunk[Byte]
  ): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data))

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

  override def publish(subject: String, data: Chunk[Byte]): IO[NatsError, PublishAck] =
    ZIO.attemptBlocking(js.publish(subject, data.toArray)).mapError(NatsError.fromThrowable)

  override def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]]
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject, data, headers = headers)
    ZIO.attemptBlocking(js.publish(msg)).mapError(NatsError.fromThrowable)
  }

  override def publish(
    subject: String,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck] =
    ZIO.attemptBlocking(js.publish(subject, data.toArray, options)).mapError(NatsError.fromThrowable)

  override def publish(
    subject: String,
    data: Chunk[Byte],
    headers: Map[String, List[String]],
    options: PublishOptions
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject, data, headers = headers)
    ZIO.attemptBlocking(js.publish(msg, options)).mapError(NatsError.fromThrowable)
  }

  override def publishAsync(
    subject: String,
    data: Chunk[Byte]
  ): IO[NatsError, Task[PublishAck]] =
    ZIO.attempt {
      val future = js.publishAsync(subject, data.toArray)
      ZIO.fromCompletionStage(future)
    }.mapError(NatsError.fromThrowable)

  override def publishAsync(
    subject: String,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, Task[PublishAck]] =
    ZIO.attempt {
      val future = js.publishAsync(subject, data.toArray, options)
      ZIO.fromCompletionStage(future)
    }.mapError(NatsError.fromThrowable)

  override def streamContext(streamName: String): IO[NatsError, JStreamContext] =
    ZIO.attemptBlocking(js.getStreamContext(streamName)).mapError(NatsError.fromThrowable)

  override def consumerContext(
    streamName: String,
    consumerName: String
  ): IO[NatsError, JConsumerContext] =
    ZIO.attemptBlocking(js.getConsumerContext(streamName, consumerName))
      .mapError(NatsError.fromThrowable)
}
