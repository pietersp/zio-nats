package zio.nats

import io.nats.client.{ConsumerContext as JConsumerContext, JetStream as JJetStream, StreamContext as JStreamContext}
import zio.*

/** JetStream publishing service. */
trait JetStream {

  /** Publish raw bytes to a JetStream subject (synchronous server ack). */
  def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, PublishAck]

  /** Publish raw bytes with NATS [[Headers]]. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Headers
  ): IO[NatsError, PublishAck]

  /**
   * Publish raw bytes with [[PublishOptions]] (message ID, expected-sequence,
   * etc.).
   */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /** Publish raw bytes with both [[Headers]] and [[PublishOptions]]. */
  def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Headers,
    options: PublishOptions
  ): IO[NatsError, PublishAck]

  /**
   * Encode `data` using the implicit [[NatsCodec]] and publish to `subject`.
   */
  def publish[T: NatsCodec](subject: Subject, data: T): IO[NatsError, PublishAck]

  def publishAsync(subject: Subject, data: Chunk[Byte]): IO[NatsError, Task[PublishAck]]

  def publishAsync(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, Task[PublishAck]]

  /** Get a [[Consumer]] handle for a named durable consumer. */
  def consumer(streamName: String, consumerName: String): IO[NatsError, Consumer]
}

object JetStream {

  def publish(subject: Subject, data: Chunk[Byte]): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data))

  def publish[T: NatsCodec](subject: Subject, data: T): ZIO[JetStream, NatsError, PublishAck] =
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

  /** Create from a [[Nats]] connection. */
  val live: ZLayer[Nats, NatsError, JetStream] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        js   <- ZIO
                .attempt(nats.underlying.jetStream())
                .mapError(NatsError.fromThrowable)
      } yield new JetStreamLive(js)
    }
}

private[nats] final class JetStreamLive(js: JJetStream) extends JetStream {

  override def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, PublishAck] =
    ZIO
      .attemptBlocking(js.publish(subject.value, data.toArray))
      .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Headers
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO
      .attemptBlocking(js.publish(msg))
      .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
  }

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    options: PublishOptions
  ): IO[NatsError, PublishAck] =
    ZIO
      .attemptBlocking(js.publish(subject.value, data.toArray, options.toJava))
      .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)

  override def publish(
    subject: Subject,
    data: Chunk[Byte],
    headers: Headers,
    options: PublishOptions
  ): IO[NatsError, PublishAck] = {
    val msg = NatsMessage.toJava(subject.value, data, headers = headers)
    ZIO
      .attemptBlocking(js.publish(msg, options.toJava))
      .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
  }

  override def publish[T: NatsCodec](subject: Subject, data: T): IO[NatsError, PublishAck] = {
    val bytes = NatsCodec[T].encode(data)
    ZIO
      .attemptBlocking(js.publish(subject.value, bytes.toArray))
      .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
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
    ZIO
      .attemptBlocking(js.getConsumerContext(streamName, consumerName))
      .mapBoth(NatsError.fromThrowable, ctx => new ConsumerLive(streamName, consumerName, ctx))

  private[nats] def streamContext(streamName: String): IO[NatsError, JStreamContext] =
    ZIO.attemptBlocking(js.getStreamContext(streamName)).mapError(NatsError.fromThrowable)

  private[nats] def consumerContext(
    streamName: String,
    consumerName: String
  ): IO[NatsError, JConsumerContext] =
    ZIO
      .attemptBlocking(js.getConsumerContext(streamName, consumerName))
      .mapError(NatsError.fromThrowable)
}
