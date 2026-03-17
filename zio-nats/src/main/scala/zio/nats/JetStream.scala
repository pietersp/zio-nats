package zio.nats

import io.nats.client.{ConsumerContext as JConsumerContext, JetStream as JJetStream, StreamContext as JStreamContext}
import zio.*

/** JetStream publishing service. */
trait JetStream {

  /** Publish raw bytes to a JetStream subject (synchronous server ack). */
  def publish(subject: Subject, data: Chunk[Byte], params: JsPublishParams = JsPublishParams.empty): IO[NatsError, PublishAck]

  /**
   * Encode `data` using the implicit [[NatsCodec]] and publish to `subject`.
   *
   * Use the [[JetStream]] companion accessor for the common case with no
   * custom params:
   * {{{
   *   JetStream.publish(subject, value)
   * }}}
   */
  def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, PublishAck]

  /** Publish raw bytes asynchronously (returns a Task that resolves to the ack). */
  def publishAsync(subject: Subject, data: Chunk[Byte], params: JsPublishParams = JsPublishParams.empty): IO[NatsError, Task[PublishAck]]

  /**
   * Encode `data` and publish asynchronously.
   *
   * Use the [[JetStream]] companion accessor for the common case with no
   * custom params:
   * {{{
   *   JetStream.publishAsync(subject, value)
   * }}}
   */
  def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, Task[PublishAck]]

  /** Get a [[Consumer]] handle for a named durable consumer. */
  def consumer(streamName: String, consumerName: String): IO[NatsError, Consumer]
}

object JetStream {

  def publish(subject: Subject, data: Chunk[Byte], params: JsPublishParams = JsPublishParams.empty): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data, params))

  /** Typed publish with explicit params. */
  def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data, params))

  /** Typed publish — convenience overload with [[JsPublishParams.empty]]. */
  def publish[T: NatsCodec](subject: Subject, data: T): ZIO[JetStream, NatsError, PublishAck] =
    ZIO.serviceWithZIO[JetStream](_.publish(subject, data, JsPublishParams.empty))

  def publishAsync(subject: Subject, data: Chunk[Byte], params: JsPublishParams = JsPublishParams.empty): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data, params))

  /** Typed publishAsync with explicit params. */
  def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data, params))

  /** Typed publishAsync — convenience overload with [[JsPublishParams.empty]]. */
  def publishAsync[T: NatsCodec](subject: Subject, data: T): ZIO[JetStream, NatsError, Task[PublishAck]] =
    ZIO.serviceWithZIO[JetStream](_.publishAsync(subject, data, JsPublishParams.empty))

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

  override def publish(subject: Subject, data: Chunk[Byte], params: JsPublishParams): IO[NatsError, PublishAck] =
    (params.headers.nonEmpty, params.options) match {
      case (false, None) =>
        ZIO
          .attemptBlocking(js.publish(subject.value, data.toArray))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (true, None) =>
        val msg = NatsMessage.toJava(subject.value, data, headers = params.headers)
        ZIO
          .attemptBlocking(js.publish(msg))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (false, Some(opts)) =>
        ZIO
          .attemptBlocking(js.publish(subject.value, data.toArray, opts.toJava))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
      case (true, Some(opts)) =>
        val msg = NatsMessage.toJava(subject.value, data, headers = params.headers)
        ZIO
          .attemptBlocking(js.publish(msg, opts.toJava))
          .mapBoth(NatsError.fromThrowable, PublishAck.fromJava)
    }

  override def publish[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, PublishAck] =
    publish(subject, NatsCodec[T].encode(data), params)

  override def publishAsync(subject: Subject, data: Chunk[Byte], params: JsPublishParams): IO[NatsError, Task[PublishAck]] =
    ZIO.attempt {
      val future = params.options match {
        case None       => js.publishAsync(subject.value, data.toArray)
        case Some(opts) => js.publishAsync(subject.value, data.toArray, opts.toJava)
      }
      ZIO.fromCompletionStage(future).map(PublishAck.fromJava)
    }.mapError(NatsError.fromThrowable)

  override def publishAsync[T: NatsCodec](subject: Subject, data: T, params: JsPublishParams): IO[NatsError, Task[PublishAck]] =
    publishAsync(subject, NatsCodec[T].encode(data), params)

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
