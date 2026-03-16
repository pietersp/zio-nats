package zio.nats

import io.nats.client.{ConsumeOptions, FetchConsumeOptions, ConsumerContext as JConsumerContext}
import zio.*
import zio.stream.*

/** Provides ZStream-based consumption from JetStream consumers.
  *
  * Uses the simplified consumer API (available since jnats 2.16.14).
  * All resources (FetchConsumer, IterableConsumer, MessageConsumer) are
  * automatically closed when the ZStream is interrupted or finishes.
  */
private[nats] object JetStreamConsumer {

  /** Consume messages indefinitely via callback, as a ZStream.
    *
    * Messages are delivered to the stream in the order they arrive.
    * Each message should be ack'd or nak'd explicitly.
    *
    * @param consumerCtx  a JConsumerContext from JetStream.consumerContext
    * @param options       optional ConsumeOptions (batch size, heartbeat interval, etc.)
    */
  def consume(
    consumerCtx: JConsumerContext,
    options: Option[ConsumeOptions] = None
  ): ZStream[Any, NatsError, NatsMessage] =
    ZStream.unwrapScoped {
      for {
        queue    <- ZIO.acquireRelease(Queue.unbounded[NatsMessage])(_.shutdown)
        _        <- ZIO.acquireRelease(
                      ZIO.attemptBlocking {
                        val handler: io.nats.client.MessageHandler = { msg =>
                          zio.Unsafe.unsafe { implicit u =>
                            zio.Runtime.default.unsafe
                              .run(queue.offer(NatsMessage.fromJava(msg)))
                              .getOrThrowFiberFailure()
                          }
                        }
                        options match {
                          case Some(opts) => consumerCtx.consume(opts, handler)
                          case None       => consumerCtx.consume(handler)
                        }
                      }.mapError(NatsError.fromThrowable)
                    )(mc => ZIO.attemptBlocking(mc.close()).ignoreLogged)
      } yield ZStream.fromQueue(queue)
    }

  /** Fetch a bounded batch of messages as a ZStream.
    *
    * The stream completes after the batch is fulfilled or the expiry time elapses.
    * Each message should be explicitly ack'd.
    *
    * @param consumerCtx  a JConsumerContext
    * @param options       FetchConsumeOptions (maxMessages, maxBytes, expiresIn)
    */
  def fetch(
    consumerCtx: JConsumerContext,
    options: FetchConsumeOptions
  ): ZStream[Any, NatsError, NatsMessage] =
    ZStream.unwrapScoped {
      for {
        fc <- ZIO.acquireRelease(
                ZIO.attemptBlocking(consumerCtx.fetch(options))
                  .mapError(NatsError.fromThrowable)
              )(fc => ZIO.attemptBlocking(fc.close()).ignoreLogged)
      } yield ZStream.repeatZIOOption {
        ZIO.attemptBlocking(Option(fc.nextMessage()))
          .mapError(e => Some(NatsError.fromThrowable(e)))
          .flatMap {
            case Some(msg) => ZIO.succeed(NatsMessage.fromJava(msg))
            case None      => ZIO.fail(None) // batch complete; terminates stream
          }
      }
    }

  /** Iterate over messages as a long-running pull-based ZStream.
    *
    * Pulls are managed automatically. Messages are delivered one-at-a-time.
    *
    * @param consumerCtx  a JConsumerContext
    * @param options       optional ConsumeOptions
    * @param pollTimeout   how long each nextMessage() call waits before retrying
    */
  def iterate(
    consumerCtx: JConsumerContext,
    options: Option[ConsumeOptions] = None,
    pollTimeout: Duration = 5.seconds
  ): ZStream[Any, NatsError, NatsMessage] =
    ZStream.unwrapScoped {
      for {
        ic <- ZIO.acquireRelease(
                ZIO.attemptBlocking {
                  options match {
                    case Some(opts) => consumerCtx.iterate(opts)
                    case None       => consumerCtx.iterate()
                  }
                }.mapError(NatsError.fromThrowable)
              )(ic => ZIO.attemptBlocking(ic.close()).ignoreLogged)
      } yield ZStream.repeatZIOOption {
        ZIO.attemptBlocking(Option(ic.nextMessage(pollTimeout.asJava)))
          .mapError(e => Some(NatsError.fromThrowable(e)))
          .flatMap {
            case Some(msg) => ZIO.succeed(NatsMessage.fromJava(msg))
            case None      => ZIO.fail(None) // poll timed out; stream will retry
          }
      }
    }

  /** Fetch a single next message from the consumer (blocks up to timeout).
    *
    * Returns None if no message is available within the timeout.
    */
  def next(
    consumerCtx: JConsumerContext,
    timeout: Duration = 5.seconds
  ): IO[NatsError, Option[NatsMessage]] =
    ZIO.attemptBlocking(Option(consumerCtx.next(timeout.asJava)))
      .mapError(NatsError.fromThrowable)
      .map(_.map(NatsMessage.fromJava))
}
