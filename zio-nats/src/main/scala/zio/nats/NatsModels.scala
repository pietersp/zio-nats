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

final case class PublishAck(stream: String, seqno: Long, isDuplicate: Boolean, domain: Option[String])

private[nats] object PublishAck {
  def fromJava(a: JPublishAck): PublishAck = PublishAck(
    stream = a.getStream,
    seqno = a.getSeqno,
    isDuplicate = a.isDuplicate,
    domain = Option(a.getDomain)
  )
}

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

final case class PurgeSummary(purgedCount: Long)

private[nats] object PurgeSummary {
  def fromJava(r: JPurgeResponse): PurgeSummary = PurgeSummary(r.getPurged)
}

// ---------------------------------------------------------------------------
// Key-Value return type
// ---------------------------------------------------------------------------

final case class KeyValueEntry(
  key: String,
  value: Chunk[Byte],
  revision: Long,
  operation: KeyValueOperation,
  bucketName: String
) {
  def valueAsString: String = new String(value.toArray, java.nio.charset.StandardCharsets.UTF_8)
  def decode[A: NatsCodec]: Either[NatsDecodeError, A] = NatsCodec[A].decode(value)
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

// ---------------------------------------------------------------------------
// Object Store return type
// ---------------------------------------------------------------------------

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
