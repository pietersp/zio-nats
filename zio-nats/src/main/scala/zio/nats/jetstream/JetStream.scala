package zio.nats.jetstream

import io.nats.client.{JetStream as JJetStream, StreamContext as JStreamContext}
import zio.*
import zio.nats.{Nats, NatsCodec, NatsError, NatsMessage, Subject}

/**
 * JetStream publishing service.
 *
 * JetStream is NATS's built-in persistence layer. Messages published through
 * this service are stored in server-side streams and can be replayed,
 * acknowledged, and consumed by durable consumers.
 *
 * Obtain an instance via [[JetStream.live]] (requires a [[zio.nats.Nats]] connection
 * in scope). Use [[JetStreamManagement]] for stream and consumer administration.
 *
 * ==Example==
 * {{{
 * for {
 *   js  <- ZIO.service[JetStream]
 *   ack <- js.publish(Subject("orders.created"), order)
 *   _   <- Console.printLine(s"Stored at seq ${ack.seqno}")
 * } yield ()
 * }}}
 */
trait JetStream {

  /**
   * Encode `data` using the given [[NatsCodec]] and publish to `subject`.
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

  /** Get an [[OrderedConsumer]] handle for the given stream. */
  def orderedConsumer(streamName: String, config: OrderedConsumerConfig): IO[NatsError, OrderedConsumer]
}

object JetStream {

  /** Create from a [[zio.nats.Nats]] connection. */
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

  override def orderedConsumer(streamName: String, config: OrderedConsumerConfig): IO[NatsError, OrderedConsumer] =
    ZIO
      .attemptBlocking(js.getStreamContext(streamName).createOrderedConsumer(config.toJava))
      .mapBoth(NatsError.fromThrowable, ctx => new OrderedConsumerLive(streamName, ctx))

  private[nats] def streamContext(streamName: String): IO[NatsError, JStreamContext] =
    ZIO.attemptBlocking(js.getStreamContext(streamName)).mapError(NatsError.fromThrowable)
}
