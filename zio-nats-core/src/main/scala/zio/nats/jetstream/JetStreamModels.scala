package zio.nats.jetstream

import io.nats.client.api.{
  ConsumerInfo as JConsumerInfo,
  ConsumerPauseResponse as JConsumerPauseResponse,
  PublishAck as JPublishAck,
  PurgeResponse as JPurgeResponse,
  StreamInfo as JStreamInfo
}
import io.nats.client.{FetchConsumeOptions, ConsumeOptions as JConsumeOptions, PublishOptions as JPublishOptions}
import zio.*
import zio.nats.Headers

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// Type aliases for raw Java types exposed in the API
// ---------------------------------------------------------------------------

/**
 * Raw server info for a single stored JetStream message. Returned by
 * [[JetStreamManagement.getMessage]].
 */
type MessageInfo = io.nats.client.api.MessageInfo

/**
 * JetStream account-level statistics. Returned by
 * [[JetStreamManagement.getAccountStatistics]].
 */
type AccountStatistics = io.nats.client.api.AccountStatistics

// ---------------------------------------------------------------------------
// JsEnvelope — typed value + raw JetStreamMessage
// ---------------------------------------------------------------------------

/**
 * A decoded JetStream message value paired with its raw [[JetStreamMessage]].
 *
 * Returned by [[Consumer]] and [[OrderedConsumer]] so callers have access to
 * both the decoded payload and the full message for acknowledgement operations
 * and metadata (headers, subject, reply-to).
 *
 * {{{
 * consumer.fetch[Order]().map { env =>
 *   // env.value   — the decoded Order
 *   // env.message — the full JetStreamMessage (ack, nak, headers, etc.)
 * }
 * }}}
 *
 * @param value
 *   The decoded payload.
 * @param message
 *   The raw [[JetStreamMessage]] that was received.
 */
final case class JsEnvelope[+A](value: A, message: JetStreamMessage)

// ---------------------------------------------------------------------------
// JetStream publish types
// ---------------------------------------------------------------------------

/**
 * Acknowledgment returned by the server after a JetStream publish.
 *
 * @param stream
 *   The name of the stream that stored the message.
 * @param seqno
 *   The stream sequence number assigned to the message.
 * @param isDuplicate
 *   True if the message was detected as a duplicate (same message ID).
 * @param domain
 *   The JetStream domain, if the server is configured with one.
 */
final case class PublishAck(stream: String, seqno: Long, isDuplicate: Boolean, domain: Option[String])

private[nats] object PublishAck {
  def fromJava(a: JPublishAck): PublishAck = PublishAck(
    stream = a.getStream,
    seqno = a.getSeqno,
    isDuplicate = a.isDuplicate,
    domain = Option(a.getDomain)
  )
}

/**
 * Idempotence and expected-state guards for a JetStream publish.
 *
 * All fields are optional; omit the ones you don't need.
 *
 * @param messageId
 *   A client-assigned ID used for duplicate detection. If the server has
 *   already stored a message with this ID in the stream's duplicate window, the
 *   publish is acknowledged but [[PublishAck.isDuplicate]] will be true.
 * @param expectedStream
 *   Fail the publish if the subject does not route to this stream.
 * @param expectedLastMsgId
 *   Fail if the last message stored in the stream had a different message ID.
 * @param expectedLastSeqno
 *   Fail if the stream's last sequence number is not this value.
 * @param expectedLastSubjectSeqno
 *   Fail if the last sequence on the specific subject is not this value.
 */
final case class PublishOptions(
  messageId: Option[String] = None,
  expectedStream: Option[String] = None,
  expectedLastMsgId: Option[String] = None,
  expectedLastSeqno: Option[Long] = None,
  expectedLastSubjectSeqno: Option[Long] = None
) {
  private[nats] def toJava: JPublishOptions = {
    val b = JPublishOptions.builder()
    messageId.foreach(b.messageId)
    expectedStream.foreach(b.expectedStream)
    expectedLastMsgId.foreach(b.expectedLastMsgId)
    expectedLastSeqno.foreach(b.expectedLastSequence)
    expectedLastSubjectSeqno.foreach(b.expectedLastSubjectSequence)
    b.build()
  }
}

// ---------------------------------------------------------------------------
// JetStream publish params
// ---------------------------------------------------------------------------

/**
 * Optional parameters for a JetStream publish operation.
 *
 * @param headers
 *   Optional NATS headers to attach to the message.
 * @param options
 *   Optional [[PublishOptions]] for idempotence and expected-state guards.
 */
final case class JsPublishParams(
  headers: Headers = Headers.empty,
  options: Option[PublishOptions] = None
)

object JsPublishParams {
  val empty: JsPublishParams = JsPublishParams()
}

// ---------------------------------------------------------------------------
// Consumer fetch / consume options
// ---------------------------------------------------------------------------

/**
 * Options for a bounded [[Consumer.fetch]] operation.
 *
 * @param maxMessages
 *   Maximum number of messages to fetch in a single batch (default: 100).
 * @param maxBytes
 *   Maximum total bytes to fetch. Ignored when set to -1 (default).
 * @param expiresIn
 *   How long to wait for messages before the fetch expires (default: 5
 *   seconds). The stream completes when the batch is full or this timeout
 *   elapses.
 */
final case class FetchOptions(
  maxMessages: Int = 100,
  maxBytes: Long = -1,
  expiresIn: Duration = 5.seconds
) {
  private[nats] def toJava: FetchConsumeOptions = {
    val b = FetchConsumeOptions
      .builder()
      .maxMessages(maxMessages)
      .expiresIn(expiresIn.toMillis)
    if (maxBytes > 0) b.maxBytes(maxBytes)
    b.build()
  }
}

object FetchOptions {
  val default: FetchOptions = FetchOptions()
}

/**
 * Options for an unbounded [[Consumer.consume]] or [[Consumer.iterate]]
 * operation.
 *
 * @param batchSize
 *   Number of messages the server sends per credit replenishment (default:
 *   512). Ignored when `batchBytes` is set.
 * @param batchBytes
 *   Maximum bytes per credit replenishment. Takes precedence over `batchSize`
 *   when set to a positive value. Ignored when -1 (default).
 * @param expiresIn
 *   How long each server-side subscription credit lasts before renewal
 *   (default: 30 seconds).
 */
final case class ConsumeOptions(
  batchSize: Int = 512,
  batchBytes: Long = -1,
  expiresIn: Duration = 30.seconds
) {
  private[nats] def toJava: JConsumeOptions = {
    val b = JConsumeOptions
      .builder()
      .expiresIn(expiresIn.toMillis)
    if (batchBytes > 0) b.batchBytes(batchBytes)
    else b.batchSize(batchSize)
    b.build()
  }
}

object ConsumeOptions {
  val default: ConsumeOptions = ConsumeOptions()
}

// ---------------------------------------------------------------------------
// JetStream management return types
// ---------------------------------------------------------------------------

/**
 * Snapshot of a JetStream stream's configuration and current state.
 *
 * @param name
 *   The stream name.
 * @param subjects
 *   Subjects this stream captures.
 * @param messageCount
 *   Number of messages currently stored in the stream.
 * @param byteCount
 *   Total bytes currently stored in the stream.
 * @param consumerCount
 *   Number of consumers attached to the stream.
 */
final case class StreamSummary(
  name: String,
  subjects: List[String],
  messageCount: Long,
  byteCount: Long,
  consumerCount: Long
)

private[nats] object StreamSummary {
  def fromJava(info: JStreamInfo): StreamSummary = {
    val cfg   = info.getConfiguration
    val state = info.getStreamState
    StreamSummary(
      name = cfg.getName,
      subjects = Option(cfg.getSubjects).map(_.asScala.toList).getOrElse(Nil),
      messageCount = state.getMsgCount,
      byteCount = state.getByteCount,
      consumerCount = state.getConsumerCount
    )
  }
}

/**
 * Snapshot of a JetStream consumer's state.
 *
 * @param name
 *   The consumer name.
 * @param streamName
 *   The stream this consumer is bound to.
 * @param numPending
 *   Messages available in the stream that have not yet been delivered.
 * @param numAckPending
 *   Delivered messages waiting for an acknowledgment.
 * @param redelivered
 *   Number of messages that have been redelivered.
 */
final case class ConsumerSummary(
  name: String,
  streamName: String,
  numPending: Long,
  numAckPending: Long,
  redelivered: Long
)

private[nats] object ConsumerSummary {
  def fromJava(info: JConsumerInfo): ConsumerSummary = ConsumerSummary(
    name = info.getName,
    streamName = info.getStreamName,
    numPending = info.getNumPending,
    numAckPending = info.getNumAckPending,
    redelivered = info.getRedelivered
  )
}

/**
 * Result of a stream purge operation.
 *
 * @param purgedCount
 *   The number of messages removed from the stream.
 */
final case class PurgeSummary(purgedCount: Long)

private[nats] object PurgeSummary {
  def fromJava(r: JPurgeResponse): PurgeSummary = PurgeSummary(r.getPurged)
}

// ---------------------------------------------------------------------------
// Consumer pause response
// ---------------------------------------------------------------------------

/**
 * Result of a [[JetStreamManagement.pauseConsumer]] or
 * [[JetStreamManagement.resumeConsumer]] call.
 *
 * @param isPaused
 *   Whether the consumer is currently paused.
 * @param pauseUntil
 *   The time at which the consumer will automatically resume, if paused.
 * @param pauseRemaining
 *   Time remaining until the consumer resumes, if paused.
 */
final case class ConsumerPauseInfo(
  isPaused: Boolean,
  pauseUntil: Option[java.time.ZonedDateTime],
  pauseRemaining: Option[Duration]
)

private[nats] object ConsumerPauseInfo {
  def fromJava(r: JConsumerPauseResponse): ConsumerPauseInfo = ConsumerPauseInfo(
    isPaused = r.isPaused,
    pauseUntil = Option(r.getPauseUntil),
    pauseRemaining = Option(r.getPauseRemaining).map(d => Duration.fromMillis(d.toMillis))
  )
}
