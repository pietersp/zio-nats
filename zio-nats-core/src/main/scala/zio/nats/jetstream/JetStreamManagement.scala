package zio.nats.jetstream

import io.nats.client.api.*
import io.nats.client.{PurgeOptions, JetStreamManagement as JJetStreamManagement}
import zio.*
import zio.nats.{Nats, NatsError}

import scala.jdk.CollectionConverters.*

/**
 * Service for managing JetStream streams and consumers (admin operations).
 *
 * Provides CRUD operations for streams and consumers, as well as stream
 * purging, message-level access, consumer pause/resume, and account statistics.
 *
 * Obtain an instance via [[JetStreamManagement.live]] (requires
 * [[zio.nats.Nats]] in scope). For publishing and consuming messages see
 * [[JetStream]] and [[Consumer]].
 */
trait JetStreamManagement {

  // --- Stream CRUD ---

  /** Create a new stream with the given configuration. */
  def addStream(config: StreamConfig): IO[NatsError, StreamSummary]

  /** Update the configuration of an existing stream. */
  def updateStream(config: StreamConfig): IO[NatsError, StreamSummary]

  /** Delete a stream and all its messages. Returns true if deleted. */
  def deleteStream(streamName: String): IO[NatsError, Boolean]

  /** Get metadata and state for a stream. */
  def getStreamInfo(streamName: String): IO[NatsError, StreamSummary]

  /** Delete all messages from a stream without removing the stream itself. */
  def purgeStream(streamName: String): IO[NatsError, PurgeSummary]

  /**
   * Purge messages matching `subject` from the stream.
   *
   * @param keepLast
   *   If provided, keep the last N matching messages and delete the rest.
   */
  def purgeStream(streamName: String, subject: String, keepLast: Option[Long] = None): IO[NatsError, PurgeSummary]

  /** List the names of all streams on this server. */
  def getStreamNames: IO[NatsError, List[String]]

  /** List all streams with their summaries. */
  def getStreams: IO[NatsError, List[StreamSummary]]

  // --- Consumer CRUD ---

  /** Create or update a consumer on the given stream. */
  def addOrUpdateConsumer(streamName: String, config: ConsumerConfig): IO[NatsError, ConsumerSummary]

  /** Delete a consumer from a stream. Returns true if deleted. */
  def deleteConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean]

  /** Get metadata and state for a named consumer. */
  def getConsumerInfo(streamName: String, consumerName: String): IO[NatsError, ConsumerSummary]

  /** List the names of all consumers on a stream. */
  def getConsumerNames(streamName: String): IO[NatsError, List[String]]

  /** List all consumers on a stream with their summaries. */
  def getConsumers(streamName: String): IO[NatsError, List[ConsumerSummary]]

  // --- Consumer pause / resume ---

  /**
   * Pause a consumer until a specified point in time. Paused consumers do not
   * deliver messages until `pauseUntil` is reached.
   */
  def pauseConsumer(
    streamName: String,
    consumerName: String,
    pauseUntil: java.time.ZonedDateTime
  ): IO[NatsError, ConsumerPauseInfo]

  /**
   * Resume a previously paused consumer immediately. Returns true on success.
   */
  def resumeConsumer(streamName: String, consumerName: String): IO[NatsError, Boolean]

  // --- Message access ---

  /** Retrieve a specific message by stream sequence number. */
  def getMessage(streamName: String, seq: Long): IO[NatsError, MessageInfo]

  /** Retrieve the last message stored on a subject within a stream. */
  def getLastMessage(streamName: String, subject: String): IO[NatsError, MessageInfo]

  /** Hard-delete a message by sequence number. Returns true if deleted. */
  def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean]

  // --- Account info ---

  /**
   * Retrieve account-level JetStream statistics (storage, memory, stream
   * counts, etc.).
   */
  def getAccountStatistics: IO[NatsError, AccountStatistics]
}

object JetStreamManagement {

  /** Create from a Nats connection. */
  val live: ZLayer[Nats, NatsError, JetStreamManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        conn <- nats.underlying
        jsm  <- ZIO.attempt(conn.jetStreamManagement()).mapError(NatsError.fromThrowable)
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

  override def getLastMessage(streamName: String, subject: String): IO[NatsError, MessageInfo] =
    ZIO.attemptBlocking(jsm.getLastMessage(streamName, subject)).mapError(NatsError.fromThrowable)

  override def deleteMessage(streamName: String, seq: Long): IO[NatsError, Boolean] =
    ZIO.attemptBlocking(jsm.deleteMessage(streamName, seq)).mapError(NatsError.fromThrowable)

  override def getAccountStatistics: IO[NatsError, AccountStatistics] =
    ZIO.attemptBlocking(jsm.getAccountStatistics).mapError(NatsError.fromThrowable)
}
