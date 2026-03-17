package zio.nats

import io.nats.client.{BaseConsumerContext as JBaseConsumerContext, ConsumerContext as JConsumerContext, OrderedConsumerContext as JOrderedConsumerContext}
import zio.*
import zio.stream.*

/** High-level consumer handle returned by [[JetStream.consumer]]. */
trait Consumer {
  def streamName: String
  def consumerName: String

  def fetch(options: FetchOptions = FetchOptions.default): ZStream[Any, NatsError, JetStreamMessage]
  def consume(options: ConsumeOptions = ConsumeOptions.default): ZStream[Any, NatsError, JetStreamMessage]
  def iterate(
    options: ConsumeOptions = ConsumeOptions.default,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, JetStreamMessage]
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
