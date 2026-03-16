package zio.nats

import io.nats.client.ConsumerContext as JConsumerContext
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
}

private[nats] final class ConsumerLive(
  val streamName: String,
  val consumerName: String,
  ctx: JConsumerContext
) extends Consumer {

  def fetch(options: FetchOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.fetch(ctx, options.toJava)

  def consume(options: ConsumeOptions): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.consume(ctx, Some(options.toJava))

  def iterate(options: ConsumeOptions, pollTimeout: Duration): ZStream[Any, NatsError, JetStreamMessage] =
    JetStreamConsumer.iterate(ctx, Some(options.toJava), pollTimeout)

  def next(timeout: Duration): IO[NatsError, Option[JetStreamMessage]] =
    JetStreamConsumer.next(ctx, timeout)
}
