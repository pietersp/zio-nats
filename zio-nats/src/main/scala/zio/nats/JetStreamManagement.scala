package zio.nats

import io.nats.client.{JetStreamManagement => JJetStreamManagement, PurgeOptions}
import io.nats.client.api._
import zio._
import scala.jdk.CollectionConverters._

/** Service for managing JetStream streams and consumers (admin operations). */
trait JetStreamManagement {

  // --- Stream CRUD ---
  def addStream(config: StreamConfiguration): IO[NatsError, StreamInfo]
  def updateStream(config: StreamConfiguration): IO[NatsError, StreamInfo]
  def deleteStream(streamName: String): IO[NatsError, Boolean]
  def getStreamInfo(streamName: String): IO[NatsError, StreamInfo]
  def purgeStream(streamName: String): IO[NatsError, PurgeResponse]
  def purgeStream(streamName: String, options: PurgeOptions): IO[NatsError, PurgeResponse]
  def getStreamNames: IO[NatsError, List[String]]
  def getStreams: IO[NatsError, List[StreamInfo]]

  // --- Consumer CRUD ---
  def addOrUpdateConsumer(streamName: String, config: ConsumerConfiguration): IO[NatsError, ConsumerInfo]
  def deleteConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean]
  def getConsumerInfo(streamName: String, consumerName: String): IO[NatsError, ConsumerInfo]
  def getConsumerNames(streamName: String): IO[NatsError, List[String]]
  def getConsumers(streamName: String): IO[NatsError, List[ConsumerInfo]]

  // --- Message access ---
  def getMessage(streamName: String, seq: Long): IO[NatsError, MessageInfo]
  def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean]

  // --- Account info ---
  def getAccountStatistics: IO[NatsError, AccountStatistics]
}

object JetStreamManagement {

  def addStream(config: StreamConfiguration): ZIO[JetStreamManagement, NatsError, StreamInfo] =
    ZIO.serviceWithZIO[JetStreamManagement](_.addStream(config))

  def deleteStream(name: String): ZIO[JetStreamManagement, NatsError, Boolean] =
    ZIO.serviceWithZIO[JetStreamManagement](_.deleteStream(name))

  def getStreamNames: ZIO[JetStreamManagement, NatsError, List[String]] =
    ZIO.serviceWithZIO[JetStreamManagement](_.getStreamNames)

  def addOrUpdateConsumer(
    streamName: String,
    config: ConsumerConfiguration
  ): ZIO[JetStreamManagement, NatsError, ConsumerInfo] =
    ZIO.serviceWithZIO[JetStreamManagement](_.addOrUpdateConsumer(streamName, config))

  /** Create from a Nats connection. */
  val live: ZLayer[Nats, NatsError, JetStreamManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        jsm  <- ZIO.attempt(nats.underlying.jetStreamManagement())
                  .mapError(NatsError.fromThrowable)
      } yield new JetStreamManagementLive(jsm)
    }
}

private[nats] final class JetStreamManagementLive(jsm: JJetStreamManagement) extends JetStreamManagement {

  override def addStream(config: StreamConfiguration): IO[NatsError, StreamInfo] =
    ZIO.attemptBlocking(jsm.addStream(config)).mapError(NatsError.fromThrowable)

  override def updateStream(config: StreamConfiguration): IO[NatsError, StreamInfo] =
    ZIO.attemptBlocking(jsm.updateStream(config)).mapError(NatsError.fromThrowable)

  override def deleteStream(streamName: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteStream(streamName)).mapError(NatsError.fromThrowable)

  override def getStreamInfo(streamName: String): IO[NatsError, StreamInfo] =
    ZIO.attemptBlocking(jsm.getStreamInfo(streamName)).mapError(NatsError.fromThrowable)

  override def purgeStream(streamName: String): IO[NatsError, PurgeResponse] =
    ZIO.attemptBlocking(jsm.purgeStream(streamName)).mapError(NatsError.fromThrowable)

  override def purgeStream(streamName: String, options: PurgeOptions): IO[NatsError, PurgeResponse] =
    ZIO.attemptBlocking(jsm.purgeStream(streamName, options)).mapError(NatsError.fromThrowable)

  override def getStreamNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(jsm.getStreamNames().asScala.toList).mapError(NatsError.fromThrowable)

  override def getStreams: IO[NatsError, List[StreamInfo]] =
    ZIO.attemptBlocking(jsm.getStreams().asScala.toList).mapError(NatsError.fromThrowable)

  override def addOrUpdateConsumer(
    streamName: String,
    config: ConsumerConfiguration
  ): IO[NatsError, ConsumerInfo] =
    ZIO.attemptBlocking(jsm.addOrUpdateConsumer(streamName, config)).mapError(NatsError.fromThrowable)

  override def deleteConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteConsumer(streamName, consumerName)).mapError(NatsError.fromThrowable)

  override def getConsumerInfo(streamName: String, consumerName: String): IO[NatsError, ConsumerInfo] =
    ZIO.attemptBlocking(jsm.getConsumerInfo(streamName, consumerName)).mapError(NatsError.fromThrowable)

  override def getConsumerNames(streamName: String): IO[NatsError, List[String]] =
    ZIO.attemptBlocking(jsm.getConsumerNames(streamName).asScala.toList).mapError(NatsError.fromThrowable)

  override def getConsumers(streamName: String): IO[NatsError, List[ConsumerInfo]] =
    ZIO.attemptBlocking(jsm.getConsumers(streamName).asScala.toList).mapError(NatsError.fromThrowable)

  override def getMessage(streamName: String, seq: Long): IO[NatsError, MessageInfo] =
    ZIO.attemptBlocking(jsm.getMessage(streamName, seq)).mapError(NatsError.fromThrowable)

  override def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteMessage(streamName, seq)).mapError(NatsError.fromThrowable)

  override def getAccountStatistics: IO[NatsError, AccountStatistics] =
    ZIO.attemptBlocking(jsm.getAccountStatistics()).mapError(NatsError.fromThrowable)
}
