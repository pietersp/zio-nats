package zio.nats.jetstream

import io.nats.client.{BaseConsumerContext as JBaseConsumerContext, OrderedConsumerContext as JOrderedConsumerContext}
import zio.*
import zio.stream.*
import zio.nats.{NatsCodec, NatsError}

/**
 * High-level consumer handle returned by [[JetStream.consumer]].
 *
 * Provides four consumption strategies suited to different use cases:
 *
 *   - [[fetch]] — bounded pull: retrieve up to N messages then complete.
 *   - [[consume]] — unbounded push: messages are delivered continuously until
 *     the stream is interrupted.
 *   - [[iterate]] — long-running pull: polls the server periodically.
 *   - [[next]] — single message with timeout.
 *
 * Each message is decoded into a [[JsEnvelope]] containing the typed value and
 * the raw [[JetStreamMessage]] for acknowledgement operations.
 */
trait Consumer {

  /** The name of the stream this consumer is bound to. */
  def streamName: String

  /** The durable consumer name. */
  def consumerName: String

  /**
   * Fetch a bounded batch of messages, decoding each payload as `A`.
   *
   * The returned stream completes after the batch is fulfilled or
   * [[FetchOptions.expiresIn]] elapses.
   */
  def fetch[A: NatsCodec](options: FetchOptions = FetchOptions.default): ZStream[Any, NatsError, JsEnvelope[A]]

  /**
   * Consume messages indefinitely via server-push, decoding each payload as
   * `A`.
   *
   * The stream never completes on its own — interrupt it to stop consuming.
   * Uses efficient server-side push delivery with back-pressure handled by
   * [[ConsumeOptions.batchSize]].
   */
  def consume[A: NatsCodec](options: ConsumeOptions = ConsumeOptions.default): ZStream[Any, NatsError, JsEnvelope[A]]

  /**
   * Pull messages one at a time using a long-running iterable consumer,
   * decoding each payload as `A`.
   *
   * Each poll waits up to `pollTimeout` for the next message. When no message
   * arrives within that window the poll retries automatically (the stream
   * continues). Interrupt the stream to stop.
   */
  def iterate[A: NatsCodec](
    options: ConsumeOptions = ConsumeOptions.default,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, JsEnvelope[A]]

  /**
   * Retrieve a single message decoded as `A`, waiting up to `timeout`. Returns
   * None if no message is available within the timeout.
   */
  def next[A: NatsCodec](timeout: Duration = 5.seconds): IO[NatsError, Option[JsEnvelope[A]]]

  /**
   * Unpin this consumer from the specified priority group, allowing a different
   * client to be pinned. Returns true if the server accepted the request.
   * Requires the consumer to have been created with
   * [[zio.nats.PriorityPolicy.PinnedClient]].
   */
  def unpin(group: String): IO[NatsError, Boolean]
}

/**
 * Consumer handle for an ordered consumer.
 *
 * Ordered consumers guarantee strict in-order delivery and automatically
 * re-create themselves on the server on reconnect or sequence gaps. The
 * underlying consumer name may change between calls; use
 * [[currentConsumerName]] to inspect it.
 */
trait OrderedConsumer {
  def streamName: String

  /**
   * The current server-side consumer name. Returns None until the first
   * fetch/consume.
   */
  def currentConsumerName: Option[String]

  def fetch[A: NatsCodec](options: FetchOptions = FetchOptions.default): ZStream[Any, NatsError, JsEnvelope[A]]
  def consume[A: NatsCodec](options: ConsumeOptions = ConsumeOptions.default): ZStream[Any, NatsError, JsEnvelope[A]]
  def iterate[A: NatsCodec](
    options: ConsumeOptions = ConsumeOptions.default,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, JsEnvelope[A]]
  def next[A: NatsCodec](timeout: Duration = 5.seconds): IO[NatsError, Option[JsEnvelope[A]]]

  /** Unpin this ordered consumer from the specified priority group. */
  def unpin(group: String): IO[NatsError, Boolean]
}

private[nats] object ConsumerDecode {
  def decodeMsg[A: NatsCodec](msg: JetStreamMessage): IO[NatsError, JsEnvelope[A]] =
    ZIO.fromEither(msg.decode[A]).mapBoth(e => NatsError.DecodingError(e.message, e), JsEnvelope(_, msg))

  def decodeStream[A: NatsCodec](
    stream: ZStream[Any, NatsError, JetStreamMessage]
  ): ZStream[Any, NatsError, JsEnvelope[A]] =
    stream.mapZIO(decodeMsg[A])

  def decodeNext[A: NatsCodec](opt: Option[JetStreamMessage]): IO[NatsError, Option[JsEnvelope[A]]] =
    opt match {
      case None      => ZIO.none
      case Some(msg) => decodeMsg[A](msg).map(Some(_))
    }
}

/** jnats-backed implementation of [[OrderedConsumer]]. */
private[nats] final class OrderedConsumerLive(
  val streamName: String,
  ctx: JOrderedConsumerContext
) extends OrderedConsumer {

  override def currentConsumerName: Option[String] = Option(ctx.getConsumerName)

  def fetch[A: NatsCodec](options: FetchOptions): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.fetch(ctx, options.toJava))

  def consume[A: NatsCodec](options: ConsumeOptions): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.consume(ctx, Some(options.toJava)))

  def iterate[A: NatsCodec](options: ConsumeOptions, pollTimeout: Duration): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.iterate(ctx, Some(options.toJava), pollTimeout))

  def next[A: NatsCodec](timeout: Duration): IO[NatsError, Option[JsEnvelope[A]]] =
    JetStreamConsumer.next(ctx, timeout).flatMap(ConsumerDecode.decodeNext[A])

  def unpin(group: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(ctx.unpin(group)).mapError(NatsError.fromThrowable)
}

/** jnats-backed implementation of [[Consumer]]. */
private[nats] final class ConsumerLive(
  val streamName: String,
  val consumerName: String,
  ctx: JBaseConsumerContext
) extends Consumer {

  def fetch[A: NatsCodec](options: FetchOptions): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.fetch(ctx, options.toJava))

  def consume[A: NatsCodec](options: ConsumeOptions): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.consume(ctx, Some(options.toJava)))

  def iterate[A: NatsCodec](options: ConsumeOptions, pollTimeout: Duration): ZStream[Any, NatsError, JsEnvelope[A]] =
    ConsumerDecode.decodeStream(JetStreamConsumer.iterate(ctx, Some(options.toJava), pollTimeout))

  def next[A: NatsCodec](timeout: Duration): IO[NatsError, Option[JsEnvelope[A]]] =
    JetStreamConsumer.next(ctx, timeout).flatMap(ConsumerDecode.decodeNext[A])

  def unpin(group: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(ctx.unpin(group)).mapError(NatsError.fromThrowable)
}
