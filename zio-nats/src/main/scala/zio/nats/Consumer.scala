package zio.nats

import io.nats.client.{BaseConsumerContext as JBaseConsumerContext, ConsumerContext as JConsumerContext, OrderedConsumerContext as JOrderedConsumerContext}
import zio.*
import zio.stream.*

/**
 * High-level consumer handle returned by [[JetStream.consumer]].
 *
 * Provides four consumption strategies suited to different use cases:
 *
 *   - [[fetch]] — bounded pull: retrieve up to N messages then complete.
 *   - [[consume]] — unbounded push: messages are delivered continuously until the stream is interrupted.
 *   - [[iterate]] — long-running pull: polls the server periodically.
 *   - [[next]] — single message with timeout.
 *
 * Each message must be explicitly ack'd, nak'd, or termed via
 * [[JetStreamMessage]] methods.
 */
trait Consumer {
  /** The name of the stream this consumer is bound to. */
  def streamName: String

  /** The durable consumer name. */
  def consumerName: String

  /**
   * Fetch a bounded batch of messages.
   *
   * The returned stream completes after the batch is fulfilled or
   * [[FetchOptions.expiresIn]] elapses.
   */
  def fetch(options: FetchOptions = FetchOptions.default): ZStream[Any, NatsError, JetStreamMessage]

  /**
   * Consume messages indefinitely via server-push.
   *
   * The stream never completes on its own — interrupt it to stop consuming.
   * Uses efficient server-side push delivery with back-pressure handled by
   * [[ConsumeOptions.batchSize]].
   */
  def consume(options: ConsumeOptions = ConsumeOptions.default): ZStream[Any, NatsError, JetStreamMessage]

  /**
   * Pull messages one at a time using a long-running iterable consumer.
   *
   * Each poll waits up to `pollTimeout` for the next message. When no message
   * arrives within that window the poll retries automatically (the stream
   * continues). Interrupt the stream to stop.
   */
  def iterate(
    options: ConsumeOptions = ConsumeOptions.default,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, JetStreamMessage]

  /**
   * Retrieve a single message, waiting up to `timeout`.
   * Returns None if no message is available within the timeout.
   */
  def next(timeout: Duration = 5.seconds): IO[NatsError, Option[JetStreamMessage]]

  /**
   * Unpin this consumer from the specified priority group, allowing a
   * different client to be pinned. Returns true if the server accepted the
   * request. Requires the consumer to have been created with
   * [[PriorityPolicy.PinnedClient]].
   */
  def unpin(group: String): IO[NatsError, Boolean]
}

/**
 * Consumer handle for an ordered consumer.
 *
 * Ordered consumers guarantee strict in-order delivery and automatically
 * re-create themselves on the server on reconnect or sequence gaps.
 * The underlying consumer name may change between calls; use
 * [[currentConsumerName]] to inspect it.
 */
trait OrderedConsumer {
  def streamName: String

  /** The current server-side consumer name. Returns None until the first fetch/consume. */
  def currentConsumerName: Option[String]

  def fetch(options: FetchOptions = FetchOptions.default): ZStream[Any, NatsError, JetStreamMessage]
  def consume(options: ConsumeOptions = ConsumeOptions.default): ZStream[Any, NatsError, JetStreamMessage]
  def iterate(
    options: ConsumeOptions = ConsumeOptions.default,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, JetStreamMessage]
  def next(timeout: Duration = 5.seconds): IO[NatsError, Option[JetStreamMessage]]

  /** Unpin this ordered consumer from the specified priority group. */
  def unpin(group: String): IO[NatsError, Boolean]
}

private[nats] final class OrderedConsumerLive(
  val streamName: String,
  ctx: JOrderedConsumerContext
) extends OrderedConsumer {

  override def currentConsumerName: Option[String] = Option(ctx.getConsumerName)

  def fetch(options: FetchOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.fetch(ctx, options.toJava)

  def consume(options: ConsumeOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.consume(ctx, Some(options.toJava))

  def iterate(options: ConsumeOptions, pollTimeout: Duration): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.iterate(ctx, Some(options.toJava), pollTimeout)

  def next(timeout: Duration): IO[NatsError, Option[JetStreamMessage]] =
    JetStreamConsumer.next(ctx, timeout)

  def unpin(group: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(ctx.unpin(group)).mapError(NatsError.fromThrowable)
}

private[nats] final class ConsumerLive(
  val streamName: String,
  val consumerName: String,
  ctx: JBaseConsumerContext
) extends Consumer {

  def fetch(options: FetchOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.fetch(ctx, options.toJava)

  def consume(options: ConsumeOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.consume(ctx, Some(options.toJava))

  def iterate(options: ConsumeOptions, pollTimeout: Duration): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.iterate(ctx, Some(options.toJava), pollTimeout)

  def next(timeout: Duration): IO[NatsError, Option[JetStreamMessage]] =
    JetStreamConsumer.next(ctx, timeout)

  def unpin(group: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(ctx.unpin(group)).mapError(NatsError.fromThrowable)
}
