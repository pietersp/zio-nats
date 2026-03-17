package zio.nats

import io.nats.client.api.*
import io.nats.client.{PurgeOptions, JetStreamManagement as JJetStreamManagement}
import zio.*
import zio.nats.configuration.{ConsumerConfig, StreamConfig}

import scala.jdk.CollectionConverters.*

/** Service for managing JetStream streams and consumers (admin operations). */
trait JetStreamManagement {

  // --- Stream CRUD ---
  def addStream(config: StreamConfig): IO[NatsError, StreamSummary]
  def updateStream(config: StreamConfig): IO[NatsError, StreamSummary]
  def deleteStream(streamName: String): IO[NatsError, Boolean]
  def getStreamInfo(streamName: String): IO[NatsError, StreamSummary]
  def purgeStream(streamName: String): IO[NatsError, PurgeSummary]
  def purgeStream(streamName: String, subject: String, keepLast: Option[Long] = None): IO[NatsError, PurgeSummary]
  def getStreamNames: IO[NatsError, List[String]]
  def getStreams: IO[NatsError, List[StreamSummary]]

  // --- Consumer CRUD ---
  def addOrUpdateConsumer(streamName: String, config: ConsumerConfig): IO[NatsError, ConsumerSummary]
  def deleteConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean]
  def getConsumerInfo(streamName: String, consumerName: String): IO[NatsError, ConsumerSummary]
  def getConsumerNames(streamName: String): IO[NatsError, List[String]]
  def getConsumers(streamName: String): IO[NatsError, List[ConsumerSummary]]

  // --- Consumer pause / resume ---
  def pauseConsumer(streamName: String, consumerName: String, pauseUntil: java.time.ZonedDateTime): IO[NatsError, ConsumerPauseInfo]
  def resumeConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean]

  // --- Message access ---
  def getMessage(streamName: String, seq: Long): IO[NatsError, MessageInfo]
  def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean]

  // --- Account info ---
  def getAccountStatistics: IO[NatsError, AccountStatistics]
}

object JetStreamManagement {

  def addStream(config: StreamConfig): ZIO[JetStreamManagement, NatsError, StreamSummary] =
    ZIO.serviceWithZIO[JetStreamManagement](_.addStream(config))

  def deleteStream(name: String): ZIO[JetStreamManagement, NatsError, Boolean] =
    ZIO.serviceWithZIO[JetStreamManagement](_.deleteStream(name))

  def getStreamNames: ZIO[JetStreamManagement, NatsError, List[String]] =
    ZIO.serviceWithZIO[JetStreamManagement](_.getStreamNames)

  def addOrUpdateConsumer(
    streamName: String,
    config: ConsumerConfig
  ): ZIO[JetStreamManagement, NatsError, ConsumerSummary] =
    ZIO.serviceWithZIO[JetStreamManagement](_.addOrUpdateConsumer(streamName, config))

  /** Create from a Nats connection. */
  val live: ZLayer[Nats, NatsError, JetStreamManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        jsm  <- ZIO
                 .attempt(nats.underlying.jetStreamManagement())
                 .mapError(NatsError.fromThrowable)
      } yield new JetStreamManagementLive(jsm)
    }
}

private[nats] final class JetStreamManagementLive(jsm: JJetStreamManagement) extends JetStreamManagement {

  override def addStream(config: StreamConfig): IO[NatsError, StreamSummary] =
    ZIO
      .attemptBlocking(jsm.addStream(config.toJava))
      .mapBoth(NatsError.fromThrowable, StreamSummary.fromJava)

  override def updateStream(config: StreamConfig): IO[NatsError, StreamSummary] =
    ZIO
      .attemptBlocking(jsm.updateStream(config.toJava))
      .mapBoth(NatsError.fromThrowable, StreamSummary.fromJava)

  override def deleteStream(streamName: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteStream(streamName)).mapError(NatsError.fromThrowable)

  override def getStreamInfo(streamName: String): IO[NatsError, StreamSummary] =
    ZIO
      .attemptBlocking(jsm.getStreamInfo(streamName))
      .mapBoth(NatsError.fromThrowable, StreamSummary.fromJava)

  override def purgeStream(streamName: String): IO[NatsError, PurgeSummary] =
    ZIO
      .attemptBlocking(jsm.purgeStream(streamName))
      .mapBoth(NatsError.fromThrowable, PurgeSummary.fromJava)

  override def purgeStream(
    streamName: String,
    subject: String,
    keepLast: Option[Long] = None
  ): IO[NatsError, PurgeSummary] = {
    val builder = PurgeOptions.builder().subject(subject)
    keepLast.foreach(k => builder.keep(k))
    ZIO
      .attemptBlocking(jsm.purgeStream(streamName, builder.build()))
      .mapBoth(NatsError.fromThrowable, PurgeSummary.fromJava)
  }

  override def getStreamNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(jsm.getStreamNames().asScala.toList).mapError(NatsError.fromThrowable)

  override def getStreams: IO[NatsError, List[StreamSummary]] =
    ZIO
      .attemptBlocking(jsm.getStreams().asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(StreamSummary.fromJava))

  override def addOrUpdateConsumer(
    streamName: String,
    config: ConsumerConfig
  ): IO[NatsError, ConsumerSummary] =
    ZIO
      .attemptBlocking(jsm.addOrUpdateConsumer(streamName, config.toJava))
      .mapBoth(NatsError.fromThrowable, ConsumerSummary.fromJava)

  override def deleteConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteConsumer(streamName, consumerName)).mapError(NatsError.fromThrowable)

  override def getConsumerInfo(streamName: String, consumerName: String): IO[NatsError, ConsumerSummary] =
    ZIO
      .attemptBlocking(jsm.getConsumerInfo(streamName, consumerName))
      .mapBoth(NatsError.fromThrowable, ConsumerSummary.fromJava)

  override def getConsumerNames(streamName: String): IO[NatsError, List[String]] =
    ZIO.attemptBlocking(jsm.getConsumerNames(streamName).asScala.toList).mapError(NatsError.fromThrowable)

  override def getConsumers(streamName: String): IO[NatsError, List[ConsumerSummary]] =
    ZIO
      .attemptBlocking(jsm.getConsumers(streamName).asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(ConsumerSummary.fromJava))

  override def pauseConsumer(
    streamName: String,
    consumerName: String,
    pauseUntil: java.time.ZonedDateTime
  ): IO[NatsError, ConsumerPauseInfo] =
    ZIO
      .attemptBlocking(jsm.pauseConsumer(streamName, consumerName, pauseUntil))
      .mapBoth(NatsError.fromThrowable, ConsumerPauseInfo.fromJava)

  override def resumeConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.resumeConsumer(streamName, consumerName)).mapError(NatsError.fromThrowable)

  override def getMessage(streamName: String, seq: Long): IO[NatsError, MessageInfo] =
    ZIO.attemptBlocking(jsm.getMessage(streamName, seq)).mapError(NatsError.fromThrowable)

  override def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteMessage(streamName, seq)).mapError(NatsError.fromThrowable)

  override def getAccountStatistics: IO[NatsError, AccountStatistics] =
    ZIO.attemptBlocking(jsm.getAccountStatistics).mapError(NatsError.fromThrowable)
}
