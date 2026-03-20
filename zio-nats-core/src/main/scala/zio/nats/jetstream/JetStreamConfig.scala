package zio.nats.jetstream

import io.nats.client.api.{
  External,
  Mirror,
  Republish,
  Source,
  ConsumerConfiguration as JConsumerConfiguration,
  OrderedConsumerConfiguration as JOrderedConsumerConfiguration,
  StreamConfiguration as JStreamConfiguration
}
import zio.Duration
import zio.nats.{
  AckPolicy,
  CompressionOption,
  DeliverPolicy,
  DiscardPolicy,
  PriorityPolicy,
  ReplayPolicy,
  RetentionPolicy,
  StorageType
}

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// Shared structural types (used by both StreamConfig and KeyValueConfig)
// ---------------------------------------------------------------------------

/**
 * Configures a stream to mirror another stream.
 *
 * @param name
 *   The name of the stream to mirror.
 * @param filterSubject
 *   Restrict mirroring to messages on this subject.
 * @param external
 *   External stream reference (for cross-account mirroring).
 */
case class MirrorConfig(
  name: String,
  filterSubject: Option[String] = None,
  external: Option[ExternalConfig] = None
)

/**
 * Configures an aggregate source stream to pull from.
 *
 * @param name
 *   The source stream name.
 * @param filterSubject
 *   Restrict sourcing to messages on this subject.
 * @param external
 *   External stream reference (for cross-account aggregation).
 */
case class SourceConfig(
  name: String,
  filterSubject: Option[String] = None,
  external: Option[ExternalConfig] = None
)

/**
 * External API reference for cross-account stream mirroring or sourcing.
 *
 * @param api
 *   The JetStream API prefix subject of the external account.
 * @param deliver
 *   The delivery subject prefix used for the external subscription.
 */
case class ExternalConfig(
  api: String,
  deliver: String
)

// ---------------------------------------------------------------------------
// StreamConfig
// ---------------------------------------------------------------------------

/**
 * Configuration for a JetStream stream.
 *
 * A stream captures messages published to one or more subjects and stores them
 * according to the specified retention, storage, and limit policies.
 *
 * @param name
 *   Unique stream name.
 * @param subjects
 *   Subjects whose messages are captured by this stream.
 * @param description
 *   Optional human-readable description.
 * @param maxBytes
 *   Maximum total bytes stored (-1 = unlimited).
 * @param maxMsgSize
 *   Maximum size of a single message in bytes (-1 = unlimited).
 * @param maxMsgs
 *   Maximum number of messages stored (-1 = unlimited).
 * @param maxMsgsPerSubject
 *   Maximum messages stored per subject (-1 = unlimited).
 * @param maxAge
 *   Maximum age of messages before they are expired.
 * @param duplicateWindow
 *   Window used for duplicate message detection.
 * @param storageType
 *   File or Memory storage.
 * @param discardPolicy
 *   What to do when limits are reached: discard Old (default) or New messages.
 * @param retentionPolicy
 *   Limits (default), Interest-based, or WorkQueue retention.
 * @param compressionOption
 *   Stream-level compression (default: None).
 * @param numberOfReplicas
 *   Number of server replicas (default: 1).
 * @param mirror
 *   Mirror another stream into this one.
 * @param sources
 *   Aggregate messages from other streams.
 * @param allowRollupHeaders
 *   Allow subject-level rollup via message headers.
 * @param denyDelete
 *   Prevent message deletion via admin API.
 * @param denyPurge
 *   Prevent stream purging via admin API.
 * @param allowDirect
 *   Allow direct-get (bypass consumer) for recent messages.
 * @param mirrorDirect
 *   Allow direct-get on mirrored streams.
 * @param isSealed
 *   Make the stream read-only (no new messages accepted).
 * @param firstSequence
 *   Override the starting sequence number.
 */
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
  discardPolicy: DiscardPolicy = DiscardPolicy.Old,
  retentionPolicy: RetentionPolicy = RetentionPolicy.Limits,
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

object StreamConfig {
  def apply(name: String, subjects: String*): StreamConfig =
    StreamConfig(name = name, subjects = subjects.toList)

  def toJava(config: StreamConfig): JStreamConfiguration = {
    val builder = JStreamConfiguration
      .builder()
      .name(config.name)
      .storageType(config.storageType.toJava)
      .discardPolicy(config.discardPolicy.toJava)
      .retentionPolicy(config.retentionPolicy.toJava)
      .compressionOption(config.compressionOption.toJava)
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

// ---------------------------------------------------------------------------
// ConsumerConfig
// ---------------------------------------------------------------------------

/**
 * Configuration for a JetStream consumer.
 *
 * A consumer is a server-side cursor into a stream. Durable consumers persist
 * across reconnects; ephemeral consumers are cleaned up automatically.
 *
 * @param durableName
 *   Name for a durable consumer (omit for ephemeral).
 * @param deliverSubject
 *   Subject to push messages to (push-based consumers only).
 * @param deliverGroup
 *   Queue group for push-based load balancing.
 * @param description
 *   Optional human-readable description.
 * @param filterSubject
 *   Filter messages by subject.
 * @param filterSubjects
 *   Filter messages by multiple subjects.
 * @param deliverPolicy
 *   Where to start delivering: All, Last, New, ByStartSequence, etc.
 * @param startSeq
 *   Starting sequence (used with DeliverPolicy.ByStartSequence).
 * @param startTime
 *   Starting time (used with DeliverPolicy.ByStartTime, as
 *   [[java.time.Instant]]).
 * @param ackPolicy
 *   How messages must be acknowledged: Explicit (default), All, or None.
 * @param ackWait
 *   Time the server waits for an ack before redelivering (default: 30s).
 * @param maxDeliver
 *   Maximum redelivery attempts (-1 = unlimited).
 * @param maxAckPending
 *   Maximum outstanding unacknowledged messages (-1 = unlimited).
 * @param idleHeartbeat
 *   Interval for server heartbeat on idle push consumers.
 * @param replayPolicy
 *   Instant (default) or Original (replays at original publish rate).
 * @param rateLimit
 *   Maximum message delivery rate in bits/sec (-1 = unlimited).
 * @param sampleFrequency
 *   Sampling percentage for consumer metrics (e.g. "100%").
 * @param maxPullWaiting
 *   Maximum waiting pull requests (-1 = unlimited).
 * @param maxBatch
 *   Maximum messages per pull batch (-1 = unlimited).
 * @param maxExpires
 *   Maximum expiry for a pull request.
 * @param maxBytes
 *   Maximum bytes per pull batch (-1 = unlimited).
 * @param headersOnly
 *   Deliver only headers, omit message bodies.
 * @param backoff
 *   Redelivery backoff schedule (list of delays between attempts).
 * @param metadata
 *   Arbitrary key-value metadata stored with the consumer.
 * @param pauseUntil
 *   Create the consumer in a paused state until this time.
 * @param priorityPolicy
 *   Priority delivery policy (None, Overflow, Prioritized, PinnedClient).
 * @param priorityGroups
 *   Priority group names for PinnedClient policy.
 */
case class ConsumerConfig(
  durableName: Option[String] = None,
  deliverSubject: Option[String] = None,
  deliverGroup: Option[String] = None,
  description: Option[String] = None,
  filterSubject: Option[String] = None,
  filterSubjects: List[String] = Nil,
  deliverPolicy: DeliverPolicy = DeliverPolicy.All,
  startSeq: Long = 0,
  startTime: Option[java.time.Instant] = None,
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
  headersOnly: Boolean = false,
  backoff: List[Duration] = Nil,
  metadata: Map[String, String] = Map.empty,
  pauseUntil: Option[java.time.ZonedDateTime] = None,
  priorityPolicy: Option[PriorityPolicy] = None,
  priorityGroups: List[String] = Nil
) {
  def toJava: JConsumerConfiguration = ConsumerConfig.toJava(this)
}

object ConsumerConfig {
  def durable(name: String): ConsumerConfig = ConsumerConfig(durableName = Some(name))

  def toJava(config: ConsumerConfig): JConsumerConfiguration = {
    val builder = JConsumerConfiguration
      .builder()
      .deliverPolicy(config.deliverPolicy.toJava)
      .ackPolicy(config.ackPolicy.toJava)
      .replayPolicy(config.replayPolicy.toJava)

    config.durableName.foreach(n => builder.durable(n))
    config.deliverSubject.foreach(s => builder.deliverSubject(s))
    config.deliverGroup.foreach(g => builder.deliverGroup(g))
    config.description.foreach(d => builder.description(d))
    config.filterSubject.foreach(s => builder.filterSubject(s))
    if (config.filterSubjects.nonEmpty) builder.filterSubjects(config.filterSubjects.asJava)

    if (config.startSeq > 0) builder.startSequence(config.startSeq)
    config.startTime.foreach(t => builder.startTime(t.atZone(java.time.ZoneOffset.UTC)))
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
    if (config.backoff.nonEmpty)
      builder.backoff(config.backoff.map(d => java.time.Duration.ofMillis(d.toMillis))*)
    if (config.metadata.nonEmpty) builder.metadata(config.metadata.asJava)
    config.pauseUntil.foreach(t => builder.pauseUntil(t))
    config.priorityPolicy.foreach(p => builder.priorityPolicy(p.toJava))
    if (config.priorityGroups.nonEmpty) builder.priorityGroups(config.priorityGroups.asJava)

    builder.build()
  }
}

// ---------------------------------------------------------------------------
// OrderedConsumerConfig
// ---------------------------------------------------------------------------

/**
 * Configuration for an ordered consumer.
 *
 * Ordered consumers automatically re-create themselves on the server on
 * reconnect or sequence gaps, ensuring strict in-order delivery without manual
 * ack.
 *
 * @param filterSubjects
 *   Subjects to filter on (default: all subjects in the stream).
 * @param deliverPolicy
 *   Where to start delivering (default: last per subject).
 * @param startSequence
 *   Starting stream sequence (used with DeliverPolicy.ByStartSequence).
 * @param startTime
 *   Starting time (used with DeliverPolicy.ByStartTime, as
 *   [[java.time.Instant]]).
 * @param replayPolicy
 *   Replay policy (default: Instant).
 * @param headersOnly
 *   Deliver only headers, skip message bodies.
 * @param consumerNamePrefix
 *   Prefix for the auto-generated consumer name.
 */
case class OrderedConsumerConfig(
  filterSubjects: List[String] = Nil,
  deliverPolicy: Option[DeliverPolicy] = None,
  startSequence: Option[Long] = None,
  startTime: Option[java.time.Instant] = None,
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
    config.deliverPolicy.foreach(p => occ.deliverPolicy(p.toJava))
    config.startSequence.foreach(s => occ.startSequence(s))
    config.startTime.foreach(t => occ.startTime(t.atZone(java.time.ZoneOffset.UTC)))
    config.replayPolicy.foreach(p => occ.replayPolicy(p.toJava))
    if (config.headersOnly) occ.headersOnly(true)
    config.consumerNamePrefix.foreach(occ.consumerNamePrefix)
    occ
  }
}
