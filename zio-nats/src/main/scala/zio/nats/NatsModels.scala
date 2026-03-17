package zio.nats

import io.nats.client.api.{
  ConsumerInfo as JConsumerInfo,
  ConsumerPauseResponse as JConsumerPauseResponse,
  KeyValueEntry as JKeyValueEntry,
  KeyValueStatus as JKeyValueStatus,
  KeyValueWatchOption as JKeyValueWatchOption,
  ObjectInfo as JObjectInfo,
  ObjectStoreStatus as JObjectStoreStatus,
  ObjectStoreWatchOption as JObjectStoreWatchOption,
  PublishAck as JPublishAck,
  PurgeResponse as JPurgeResponse,
  StreamInfo as JStreamInfo
}
import io.nats.client.{FetchConsumeOptions, ConsumeOptions as JConsumeOptions, PublishOptions as JPublishOptions, Statistics as JStatistics}
import zio.*

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// Envelope — typed value + raw NatsMessage
// ---------------------------------------------------------------------------

/**
 * A decoded message value paired with its raw [[NatsMessage]].
 *
 * Returned by [[Nats.request]] and [[Nats.subscribe]] so callers have access
 * to both the decoded payload and the full message metadata (headers,
 * subject, reply-to, raw bytes).
 *
 * {{{
 * Nats.subscribe[UserEvent](subject).map { env =>
 *   // env.value   — the decoded UserEvent
 *   // env.message — the full NatsMessage (headers, subject, etc.)
 * }
 * }}}
 *
 * @param value
 *   The decoded payload.
 * @param message
 *   The raw [[NatsMessage]] that was received.
 */
final case class Envelope[+A](value: A, message: NatsMessage)

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
// Connection statistics
// ---------------------------------------------------------------------------

/**
 * Lifetime counters for a NATS connection.
 *
 * All values are monotonically increasing totals since the connection was
 * established. Obtained via [[Nats.statistics]].
 */
final case class ConnectionStats(
  inMsgs: Long,
  outMsgs: Long,
  inBytes: Long,
  outBytes: Long,
  reconnects: Long,
  droppedCount: Long,
  pings: Long,
  oks: Long,
  errs: Long,
  exceptions: Long,
  requestsSent: Long,
  repliesReceived: Long,
  flushCounter: Long,
  outstandingRequests: Long
)

private[nats] object ConnectionStats {
  def fromJava(s: JStatistics): ConnectionStats = ConnectionStats(
    inMsgs = s.getInMsgs,
    outMsgs = s.getOutMsgs,
    inBytes = s.getInBytes,
    outBytes = s.getOutBytes,
    reconnects = s.getReconnects,
    droppedCount = s.getDroppedCount,
    pings = s.getPings,
    oks = s.getOKs,
    errs = s.getErrs,
    exceptions = s.getExceptions,
    requestsSent = s.getRequestsSent,
    repliesReceived = s.getRepliesReceived,
    flushCounter = s.getFlushCounter,
    outstandingRequests = s.getOutstandingRequests
  )
}

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
 *   already stored a message with this ID in the stream's duplicate window,
 *   the publish is acknowledged but [[PublishAck.isDuplicate]] will be true.
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
 *   How long to wait for messages before the fetch expires (default: 5 seconds).
 *   The stream completes when the batch is full or this timeout elapses.
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
 * Options for an unbounded [[Consumer.consume]] or [[Consumer.iterate]] operation.
 *
 * @param batchSize
 *   Number of messages the server sends per credit replenishment (default: 512).
 *   Ignored when `batchBytes` is set.
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
 * @param name          The stream name.
 * @param subjects      Subjects this stream captures.
 * @param messageCount  Number of messages currently stored in the stream.
 * @param byteCount     Total bytes currently stored in the stream.
 * @param consumerCount Number of consumers attached to the stream.
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
 * @param name          The consumer name.
 * @param streamName    The stream this consumer is bound to.
 * @param numPending    Messages available in the stream that have not yet been delivered.
 * @param numAckPending Delivered messages waiting for an acknowledgment.
 * @param redelivered   Number of messages that have been redelivered.
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
 * @param purgedCount The number of messages removed from the stream.
 */
final case class PurgeSummary(purgedCount: Long)

private[nats] object PurgeSummary {
  def fromJava(r: JPurgeResponse): PurgeSummary = PurgeSummary(r.getPurged)
}

// ---------------------------------------------------------------------------
// Key-Value types
// ---------------------------------------------------------------------------

/**
 * Raw metadata for a single entry in a NATS KV bucket.
 *
 * Embedded in [[KvEnvelope]] to give callers access to revision, operation,
 * and other server-side metadata alongside the decoded value.
 *
 * @param key        The entry key.
 * @param value      Raw payload bytes. Empty for delete/purge markers.
 * @param revision   The stream sequence number (monotonically increasing).
 * @param operation  Whether this entry is a [[KeyValueOperation.Put]],
 *                   [[KeyValueOperation.Delete]], or [[KeyValueOperation.Purge]] marker.
 * @param bucketName The bucket this entry belongs to.
 */
final case class KeyValueEntry(
  key: String,
  value: Chunk[Byte],
  revision: Long,
  operation: KeyValueOperation,
  bucketName: String
) {
  private[nats] def valueAsString: String = new String(value.toArray, java.nio.charset.StandardCharsets.UTF_8)
  private[nats] def decode[A: NatsCodec]: Either[NatsDecodeError, A] = NatsCodec[A].decode(value)
}

private[nats] object KeyValueEntry {
  def fromJava(e: JKeyValueEntry): KeyValueEntry = KeyValueEntry(
    key = e.getKey,
    value = Option(e.getValue).fold(Chunk.empty[Byte])(Chunk.fromArray),
    revision = e.getRevision,
    operation = KeyValueOperation.fromJava(e.getOperation),
    bucketName = e.getBucket
  )
}

/**
 * A decoded Key-Value entry paired with its server-side [[KeyValueEntry]] metadata.
 *
 * Returned by [[KeyValue.get]], [[KeyValue.watch]], and [[KeyValue.history]] so
 * callers have access to both the decoded payload and the entry metadata
 * (key, revision, operation, bucket name).
 *
 * Delete and purge marker entries are never emitted as [[KvEnvelope]]s —
 * they are silently filtered by the library. Pass `Chunk[Byte]` as the type
 * parameter to skip decoding and receive raw bytes.
 *
 * {{{
 * kv.watch[UserProfile]("user.42").map { env =>
 *   // env.value    — the decoded UserProfile
 *   // env.key      — the KV key ("user.42")
 *   // env.revision — the stream sequence number
 *   // env.entry    — full KeyValueEntry with raw bytes and operation
 * }
 * }}}
 *
 * @param value   The decoded payload.
 * @param entry   The raw [[KeyValueEntry]] containing metadata and raw bytes.
 */
final case class KvEnvelope[+A](value: A, entry: KeyValueEntry) {
  /** The entry key. */
  def key: String = entry.key

  /** The stream sequence revision of this entry (monotonically increasing). */
  def revision: Long = entry.revision

  /** The KV operation. Always [[KeyValueOperation.Put]] for typed results. */
  def operation: KeyValueOperation = entry.operation

  /** The bucket this entry belongs to. */
  def bucketName: String = entry.bucketName
}

// ---------------------------------------------------------------------------
// Object Store return types
// ---------------------------------------------------------------------------

/**
 * A decoded object value paired with its [[ObjectSummary]] metadata.
 *
 * Returned by [[ObjectStore.get]] so callers have access to both the decoded
 * payload and the object's metadata (name, size, description, etc.).
 *
 * {{{
 * os.get[MyData]("config.json").map { obj =>
 *   // obj.value   — the decoded MyData
 *   // obj.summary — ObjectSummary (name, size, chunks, isDeleted, …)
 * }
 * }}}
 *
 * @param value
 *   The decoded payload.
 * @param summary
 *   Metadata returned by the server when retrieving the object.
 */
final case class ObjectData[+A](value: A, summary: ObjectSummary)

/**
 * Metadata for a stored object in a NATS Object Store bucket.
 *
 * @param name        The object name (unique within the bucket).
 * @param size        Total size of the object in bytes.
 * @param chunks      Number of chunks the object was split into for storage.
 * @param description Optional human-readable description.
 * @param isDeleted   True if the object has been soft-deleted.
 */
final case class ObjectSummary(
  name: String,
  size: Long,
  chunks: Long,
  description: Option[String],
  isDeleted: Boolean
)

private[nats] object ObjectSummary {
  def fromJava(info: JObjectInfo): ObjectSummary = ObjectSummary(
    name = info.getObjectName,
    size = info.getSize,
    chunks = info.getChunks,
    description = Option(info.getDescription),
    isDeleted = info.isDeleted
  )
}

// ---------------------------------------------------------------------------
// Consumer pause response
// ---------------------------------------------------------------------------

/**
 * Result of a [[JetStreamManagement.pauseConsumer]] or [[JetStreamManagement.resumeConsumer]] call.
 *
 * @param isPaused       Whether the consumer is currently paused.
 * @param pauseUntil     The time at which the consumer will automatically resume, if paused.
 * @param pauseRemaining Time remaining until the consumer resumes, if paused.
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

// ---------------------------------------------------------------------------
// Key-Value watch options
// ---------------------------------------------------------------------------

/**
 * Options that control which entries a KV watch delivers.
 *
 * @param ignoreDeletes  Skip delete and purge markers (default: include them).
 * @param metaOnly       Receive only metadata; omit value bytes (default: include values).
 * @param includeHistory Start from the first entry per key instead of the last (default: last per key).
 * @param updatesOnly    Start only from new entries written after the watch begins (default: last per key).
 * @param fromRevision   Resume from a specific stream revision (overrides the deliver-policy flags above).
 */
case class KeyValueWatchOptions(
  ignoreDeletes: Boolean = false,
  metaOnly: Boolean = false,
  includeHistory: Boolean = false,
  updatesOnly: Boolean = false,
  fromRevision: Option[Long] = None
)

object KeyValueWatchOptions {
  val default: KeyValueWatchOptions = KeyValueWatchOptions()

  private[nats] def toJava(opts: KeyValueWatchOptions): Array[JKeyValueWatchOption] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[JKeyValueWatchOption]
    if (opts.ignoreDeletes) buf += JKeyValueWatchOption.IGNORE_DELETE
    if (opts.metaOnly) buf += JKeyValueWatchOption.META_ONLY
    if (opts.includeHistory) buf += JKeyValueWatchOption.INCLUDE_HISTORY
    if (opts.updatesOnly) buf += JKeyValueWatchOption.UPDATES_ONLY
    buf.toArray
  }
}

// ---------------------------------------------------------------------------
// Key-Value bucket status
// ---------------------------------------------------------------------------

/**
 * Current status and configuration of a NATS KV bucket.
 *
 * @param bucketName       The bucket name.
 * @param description      Optional description.
 * @param entryCount       Number of live entries in the bucket.
 * @param byteCount        Total bytes stored in the bucket.
 * @param maxHistoryPerKey Maximum revisions to keep per key (-1 = unlimited).
 * @param maxBucketSize    Maximum total bytes for the bucket (-1 = unlimited).
 * @param storageType      File or Memory storage.
 * @param replicas         Number of server replicas.
 * @param isCompressed     Whether values are compressed on the server.
 * @param ttl              Default TTL for entries, if configured.
 */
final case class KeyValueBucketStatus(
  bucketName: String,
  description: Option[String],
  entryCount: Long,
  byteCount: Long,
  maxHistoryPerKey: Long,
  maxBucketSize: Long,
  storageType: StorageType,
  replicas: Int,
  isCompressed: Boolean,
  ttl: Option[Duration]
)

private[nats] object KeyValueBucketStatus {
  def fromJava(s: JKeyValueStatus): KeyValueBucketStatus = KeyValueBucketStatus(
    bucketName = s.getBucketName,
    description = Option(s.getDescription),
    entryCount = s.getEntryCount,
    byteCount = s.getByteCount,
    maxHistoryPerKey = s.getMaxHistoryPerKey,
    maxBucketSize = s.getMaxBucketSize,
    storageType = StorageType.fromJava(s.getStorageType),
    replicas = s.getReplicas,
    isCompressed = s.isCompressed,
    ttl = Option(s.getTtl).filter(_.toMillis > 0).map(d => Duration.fromMillis(d.toMillis))
  )
}

// ---------------------------------------------------------------------------
// Object Store watch options
// ---------------------------------------------------------------------------

/**
 * Options that control which entries an ObjectStore watch delivers.
 *
 * @param ignoreDeletes  Skip deleted-object entries (default: include them).
 * @param includeHistory Start from the first entry for all objects instead of the last (default: last per object).
 * @param updatesOnly    Start only from new entries written after the watch begins (default: last per object).
 */
case class ObjectStoreWatchOptions(
  ignoreDeletes: Boolean = false,
  includeHistory: Boolean = false,
  updatesOnly: Boolean = false
)

object ObjectStoreWatchOptions {
  val default: ObjectStoreWatchOptions = ObjectStoreWatchOptions()

  private[nats] def toJava(opts: ObjectStoreWatchOptions): Array[JObjectStoreWatchOption] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[JObjectStoreWatchOption]
    if (opts.ignoreDeletes) buf += JObjectStoreWatchOption.IGNORE_DELETE
    if (opts.includeHistory) buf += JObjectStoreWatchOption.INCLUDE_HISTORY
    if (opts.updatesOnly) buf += JObjectStoreWatchOption.UPDATES_ONLY
    buf.toArray
  }
}

// ---------------------------------------------------------------------------
// Object Store bucket status
// ---------------------------------------------------------------------------

/**
 * Current status and configuration of a NATS Object Store bucket.
 *
 * @param bucketName    The bucket name.
 * @param description   Optional description.
 * @param size          Total bytes stored across all objects.
 * @param maxBucketSize Maximum allowed bytes (-1 = unlimited).
 * @param storageType   File or Memory storage.
 * @param replicas      Number of server replicas.
 * @param isSealed      True if the bucket has been sealed (read-only).
 * @param isCompressed  Whether objects are compressed on the server.
 */
final case class ObjectStoreBucketStatus(
  bucketName: String,
  description: Option[String],
  size: Long,
  maxBucketSize: Long,
  storageType: StorageType,
  replicas: Int,
  isSealed: Boolean,
  isCompressed: Boolean
)

private[nats] object ObjectStoreBucketStatus {
  def fromJava(s: JObjectStoreStatus): ObjectStoreBucketStatus = ObjectStoreBucketStatus(
    bucketName = s.getBucketName,
    description = Option(s.getDescription),
    size = s.getSize,
    maxBucketSize = s.getMaxBucketSize,
    storageType = StorageType.fromJava(s.getStorageType),
    replicas = s.getReplicas,
    isSealed = s.isSealed,
    isCompressed = s.isCompressed
  )
}
