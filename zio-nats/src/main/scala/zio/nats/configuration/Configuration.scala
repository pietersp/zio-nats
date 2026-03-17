package zio.nats.configuration

import io.nats.client.api.{
  AckPolicy,
  CompressionOption,
  DeliverPolicy,
  External,
  Mirror,
  ReplayPolicy,
  Source,
  ConsumerConfiguration as JConsumerConfiguration,
  KeyValueConfiguration as JKeyValueConfiguration,
  ObjectStoreConfiguration as JObjectStoreConfiguration,
  OrderedConsumerConfiguration as JOrderedConsumerConfiguration,
  StreamConfiguration as JStreamConfiguration
}
import zio.Duration
import zio.nats.StorageType

import scala.jdk.CollectionConverters.*

case class StreamConfig(
  name: String,
  subjects: List[String] = Nil,
  description: Option[String] = None,
  maxBytes: Long = -1,
  maxMsgSize: Long = -1,
  maxMsgs: Long = -1,
  maxMsgsPerSubject: Long = -1,
  maxAge: Option[Duration] = None,
  duplicateWindow: Option[Duration] = None,
  storageType: StorageType = StorageType.File,
  discardPolicy: io.nats.client.api.DiscardPolicy = io.nats.client.api.DiscardPolicy.Old,
  retentionPolicy: io.nats.client.api.RetentionPolicy = io.nats.client.api.RetentionPolicy.Limits,
  compressionOption: CompressionOption = CompressionOption.None,
  numberOfReplicas: Int = 1,
  mirror: Option[MirrorConfig] = None,
  sources: List[SourceConfig] = Nil,
  allowRollupHeaders: Boolean = false,
  denyDelete: Boolean = false,
  denyPurge: Boolean = false,
  allowDirect: Boolean = false,
  mirrorDirect: Boolean = false,
  isSealed: Boolean = false,
  firstSequence: Long = -1
) {
  def toJava: JStreamConfiguration = StreamConfig.toJava(this)
}

case class MirrorConfig(
  name: String,
  filterSubject: Option[String] = None,
  external: Option[ExternalConfig] = None
)

case class SourceConfig(
  name: String,
  filterSubject: Option[String] = None,
  external: Option[ExternalConfig] = None
)

case class ExternalConfig(
  api: String,
  deliver: String
)

object StreamConfig {
  def apply(name: String, subjects: String*): StreamConfig =
    StreamConfig(name = name, subjects = subjects.toList)

  def toJava(config: StreamConfig): JStreamConfiguration = {
    val builder = JStreamConfiguration
      .builder()
      .name(config.name)
      .storageType(config.storageType.toJava)
      .discardPolicy(config.discardPolicy)
      .retentionPolicy(config.retentionPolicy)
      .compressionOption(config.compressionOption)
      .replicas(config.numberOfReplicas)

    if (config.subjects.nonEmpty) builder.addSubjects(config.subjects.asJava)
    config.description.foreach(d => builder.description(d))
    if (config.maxBytes > 0) builder.maxBytes(config.maxBytes)
    if (config.maxMsgSize > 0) builder.maxMsgSize(config.maxMsgSize)
    if (config.maxMsgs > 0) builder.maxMessages(config.maxMsgs)
    if (config.maxMsgsPerSubject > 0) builder.maxMessagesPerSubject(config.maxMsgsPerSubject)
    config.maxAge.foreach(d => builder.maxAge(java.time.Duration.ofMillis(d.toMillis)))
    config.duplicateWindow.foreach(d => builder.duplicateWindow(java.time.Duration.ofMillis(d.toMillis)))
    if (config.allowRollupHeaders) builder.allowRollup(true)
    if (config.denyDelete) builder.denyDelete(true)
    if (config.denyPurge) builder.denyPurge(true)
    if (config.allowDirect) builder.allowDirect(true)
    if (config.mirrorDirect) builder.mirrorDirect(true)
    if (config.isSealed) builder.seal()
    if (config.firstSequence > 0) builder.firstSequence(config.firstSequence)

    config.mirror.foreach { mirror =>
      val mirrorBuilder = Mirror.builder().name(mirror.name)
      mirror.filterSubject.foreach(s => mirrorBuilder.filterSubject(s))
      mirror.external.foreach { ext =>
        mirrorBuilder.external(External.builder().api(ext.api).deliver(ext.deliver).build())
      }
      builder.mirror(mirrorBuilder.build())
    }

    config.sources.foreach { source =>
      val sourceBuilder = Source.builder().name(source.name)
      source.filterSubject.foreach(s => sourceBuilder.filterSubject(s))
      source.external.foreach { ext =>
        sourceBuilder.external(External.builder().api(ext.api).deliver(ext.deliver).build())
      }
      builder.addSource(sourceBuilder.build())
    }

    builder.build()
  }
}

case class ConsumerConfig(
  durableName: Option[String] = None,
  deliverSubject: Option[String] = None,
  deliverGroup: Option[String] = None,
  description: Option[String] = None,
  filterSubject: Option[String] = None,
  filterSubjects: List[String] = Nil,
  deliverPolicy: DeliverPolicy = DeliverPolicy.All,
  startSeq: Long = 0,
  startTime: Option[java.time.ZonedDateTime] = None,
  ackPolicy: AckPolicy = AckPolicy.Explicit,
  ackWait: Duration = zio.Duration.fromSeconds(30),
  maxDeliver: Long = -1,
  maxAckPending: Long = -1,
  idleHeartbeat: Duration = zio.Duration.Zero,
  replayPolicy: ReplayPolicy = ReplayPolicy.Instant,
  rateLimit: Long = -1,
  sampleFrequency: Option[String] = None,
  maxPullWaiting: Long = -1,
  maxBatch: Long = -1,
  maxExpires: Duration = zio.Duration.Zero,
  maxBytes: Long = -1,
  headersOnly: Boolean = false
) {
  def toJava: JConsumerConfiguration = ConsumerConfig.toJava(this)
}

object ConsumerConfig {
  def durable(name: String): ConsumerConfig = ConsumerConfig(durableName = Some(name))

  def toJava(config: ConsumerConfig): JConsumerConfiguration = {
    val builder = JConsumerConfiguration
      .builder()
      .deliverPolicy(config.deliverPolicy)
      .ackPolicy(config.ackPolicy)
      .replayPolicy(config.replayPolicy)

    config.durableName.foreach(n => builder.durable(n))
    config.deliverSubject.foreach(s => builder.deliverSubject(s))
    config.deliverGroup.foreach(g => builder.deliverGroup(g))
    config.description.foreach(d => builder.description(d))
    config.filterSubject.foreach(s => builder.filterSubject(s))
    if (config.filterSubjects.nonEmpty) builder.filterSubjects(config.filterSubjects.asJava)

    if (config.startSeq > 0) builder.startSequence(config.startSeq)
    config.startTime.foreach(t => builder.startTime(t))
    if (config.ackWait.toMillis > 0) builder.ackWait(config.ackWait.toMillis)
    if (config.maxDeliver > 0) builder.maxDeliver(config.maxDeliver)
    if (config.maxAckPending > 0) builder.maxAckPending(config.maxAckPending)
    if (config.idleHeartbeat.toMillis > 0) builder.idleHeartbeat(config.idleHeartbeat.toMillis)
    if (config.rateLimit > 0) builder.rateLimit(config.rateLimit)
    config.sampleFrequency.foreach(s => builder.sampleFrequency(s))
    if (config.maxPullWaiting > 0) builder.maxPullWaiting(config.maxPullWaiting)
    if (config.maxBatch > 0) builder.maxBatch(config.maxBatch)
    if (config.maxExpires.toMillis > 0) builder.maxExpires(config.maxExpires.toMillis)
    if (config.maxBytes > 0) builder.maxBytes(config.maxBytes)
    if (config.headersOnly) builder.headersOnly(java.lang.Boolean.TRUE)

    builder.build()
  }
}

case class KeyValueConfig(
  name: String,
  description: Option[String] = None,
  maxValueSize: Long = -1,
  maxBucketSize: Long = -1,
  maxHistoryPerKey: Int = -1,
  storageType: StorageType = StorageType.File,
  compression: Boolean = false,
  numberOfReplicas: Int = 1,
  ttl: Option[Duration] = None,
  limitMarkerTtl: Option[Duration] = None
) {
  def toJava: JKeyValueConfiguration = KeyValueConfig.toJava(this)
}

object KeyValueConfig {
  def apply(name: String): KeyValueConfig = new KeyValueConfig(name = name)

  def toJava(config: KeyValueConfig): JKeyValueConfiguration = {
    val builder = JKeyValueConfiguration
      .builder()
      .name(config.name)
      .storageType(config.storageType.toJava)
      .compression(config.compression)
      .replicas(config.numberOfReplicas)

    config.description.foreach(d => builder.description(d))
    if (config.maxValueSize > 0) builder.maxValueSize(config.maxValueSize)
    if (config.maxBucketSize > 0) builder.maxBucketSize(config.maxBucketSize)
    if (config.maxHistoryPerKey > 0) builder.maxHistoryPerKey(config.maxHistoryPerKey)
    config.ttl.foreach(d => builder.ttl(java.time.Duration.ofMillis(d.toMillis)))
    config.limitMarkerTtl.foreach(d => builder.limitMarker(java.time.Duration.ofMillis(d.toMillis)))

    builder.build()
  }
}

/**
 * Configuration for an ordered consumer.
 *
 * Ordered consumers automatically re-create themselves on the server on reconnect or
 * sequence gaps, ensuring strict in-order delivery without manual ack.
 *
 * @param filterSubjects  Subjects to filter on (default: all subjects in the stream).
 * @param deliverPolicy   Where to start delivering (default: last per subject).
 * @param startSequence   Starting stream sequence (used with DeliverPolicy.ByStartSequence).
 * @param startTime       Starting time (used with DeliverPolicy.ByStartTime).
 * @param replayPolicy    Replay policy (default: Instant).
 * @param headersOnly     Deliver only headers, skip message bodies.
 * @param consumerNamePrefix  Prefix for the auto-generated consumer name.
 */
case class OrderedConsumerConfig(
  filterSubjects: List[String] = Nil,
  deliverPolicy: Option[DeliverPolicy] = None,
  startSequence: Option[Long] = None,
  startTime: Option[java.time.ZonedDateTime] = None,
  replayPolicy: Option[ReplayPolicy] = None,
  headersOnly: Boolean = false,
  consumerNamePrefix: Option[String] = None
) {
  def toJava: JOrderedConsumerConfiguration = OrderedConsumerConfig.toJava(this)
}

object OrderedConsumerConfig {
  def toJava(config: OrderedConsumerConfig): JOrderedConsumerConfiguration = {
    val occ = new JOrderedConsumerConfiguration()
    if (config.filterSubjects.nonEmpty) occ.filterSubjects(config.filterSubjects.asJava)
    config.deliverPolicy.foreach(occ.deliverPolicy)
    config.startSequence.foreach(s => occ.startSequence(s))
    config.startTime.foreach(t => occ.startTime(t))
    config.replayPolicy.foreach(occ.replayPolicy)
    if (config.headersOnly) occ.headersOnly(true)
    config.consumerNamePrefix.foreach(occ.consumerNamePrefix)
    occ
  }
}

case class ObjectStoreConfig(
  name: String,
  description: Option[String] = None,
  maxBucketSize: Long = -1,
  storageType: StorageType = StorageType.File
) {
  def toJava: JObjectStoreConfiguration = ObjectStoreConfig.toJava(this)
}

object ObjectStoreConfig {
  def apply(name: String): ObjectStoreConfig = new ObjectStoreConfig(name = name)

  def toJava(config: ObjectStoreConfig): JObjectStoreConfiguration = {
    val builder = JObjectStoreConfiguration
      .builder()
      .name(config.name)
      .storageType(config.storageType.toJava)

    config.description.foreach(d => builder.description(d))
    if (config.maxBucketSize > 0) builder.maxBucketSize(config.maxBucketSize)

    builder.build()
  }
}
