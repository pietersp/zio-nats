package zio.nats

import io.nats.client.api.{
  KeyValueOperation,
  StorageType,
  ConsumerInfo as JConsumerInfo,
  KeyValueEntry as JKeyValueEntry,
  KeyValueStatus as JKeyValueStatus,
  ObjectInfo as JObjectInfo,
  ObjectStoreStatus as JObjectStoreStatus,
  PublishAck as JPublishAck,
  PurgeResponse as JPurgeResponse,
  StreamInfo as JStreamInfo
}
import io.nats.client.{FetchConsumeOptions, ConsumeOptions as JConsumeOptions, PublishOptions as JPublishOptions}
import zio.*

import scala.jdk.CollectionConverters.*

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
}

private[nats] object KeyValueEntry {
  def fromJava(e: JKeyValueEntry): KeyValueEntry = KeyValueEntry(
    key = e.getKey,
    value = Chunk.fromArray(e.getValue),
    revision = e.getRevision,
    operation = e.getOperation,
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
  isCompressed: Boolean
)

private[nats] object KeyValueBucketStatus {
  def fromJava(s: JKeyValueStatus): KeyValueBucketStatus = KeyValueBucketStatus(
    bucketName = s.getBucketName,
    description = Option(s.getDescription),
    entryCount = s.getEntryCount,
    byteCount = s.getByteCount,
    maxHistoryPerKey = s.getMaxHistoryPerKey,
    maxBucketSize = s.getMaxBucketSize,
    storageType = s.getStorageType,
    replicas = s.getReplicas,
    isCompressed = s.isCompressed
  )
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
    storageType = s.getStorageType,
    replicas = s.getReplicas,
    isSealed = s.isSealed,
    isCompressed = s.isCompressed
  )
}
