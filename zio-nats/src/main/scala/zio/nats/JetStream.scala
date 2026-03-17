package zio.nats

import io.nats.client.{ConsumerContext as JConsumerContext, JetStream as JJetStream, StreamContext as JStreamContext}
import zio.*

/** JetStream publishing service. */
trait JetStream {

  /**
   * Encode `data` using the implicit [[NatsCodec]] and publish to `subject`.
   *
   * Pass `Chunk[Byte]` to use the identity codec (raw bytes).
   *
   * @param params
   *   Optional [[JsPublishParams]] (defaults to [[JsPublishParams.empty]]).
   */
  def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams = JsPublishParams.empty): IO[NatsError, PublishAck]

  /**
   * Encode `data` and publish asynchronously (returns a Task resolving to the ack).
   *
   * Pass `Chunk[Byte]` to use the identity codec (raw bytes).
   *
   * @param params
   *   Optional [[JsPublishParams]] (defaults to [[JsPublishParams.empty]]).
   */
  def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams = JsPublishParams.empty): IO[NatsError, Task[PublishAck]]

  /** Get a [[Consumer]] handle for a named durable consumer. */
  def consumer(streamName: String, consumerName: String): IO[NatsError, Consumer]
}

object JetStream {

  def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams = JsPublishParams.empty): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data, params))

  def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams = JsPublishParams.empty): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data, params))

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

  override def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, PublishAck] = {
    val bytes = NatsCodec[T].encode(data)
    (params.headers.nonEmpty, params.options) match {
      case (false, None) =>
        ZIO
          .attemptBlocking(js.publish(subject.value, bytes.toArray))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (true, None) =>
        val msg = NatsMessage.toJava(subject.value, bytes, headers = params.headers)
        ZIO
          .attemptBlocking(js.publish(msg))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (false, Some(opts)) =>
        ZIO
          .attemptBlocking(js.publish(subject.value, bytes.toArray, opts.toJava))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (true, Some(opts)) =>
        val msg = NatsMessage.toJava(subject.value, bytes, headers = params.headers)
        ZIO
          .attemptBlocking(js.publish(msg, opts.toJava))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
    }
  }

  override def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, Task[PublishAck]] = {
    val bytes = NatsCodec[T].encode(data)
    ZIO.attempt {
      val future = params.options match {
        case None       => js.publishAsync(subject.value, bytes.toArray)
        case Some(opts) => js.publishAsync(subject.value, bytes.toArray, opts.toJava)
      }
      ZIO.fromCompletionStage(future).map(PublishAck.fromJava)
    }.mapError(NatsError.fromThrowable)
  }

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
