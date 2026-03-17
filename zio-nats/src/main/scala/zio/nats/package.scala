package zio

/**
 * Package-level conveniences for zio-nats.
 *
 * Import zio.nats._ to get all services plus these helpers.
 */
package object nats {

  import zio.stream.ZStream

  /** Type alias: a ZIO effect that requires the core Nats service. */
  type NatsIO[+A] = ZIO[Nats, NatsError, A]

  /** Type alias: a ZIO effect that requires JetStream. */
  type JetStreamIO[+A] = ZIO[JetStream, NatsError, A]

  /** Implicit conversion: String -> Chunk[Byte] (UTF-8). */
  extension (s: String)
    /** Encode this string as a UTF-8 byte Chunk suitable for NATS publish. */
    def toNatsData: Chunk[Byte] =
      Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  // ---------------------------------------------------------------------------
  // Enum type aliases — the companion objects below provide value access so
  // that `StorageType.Memory` works without any io.nats.client.* imports.
  // ---------------------------------------------------------------------------

  type AckPolicy         = io.nats.client.api.AckPolicy
  type DeliverPolicy     = io.nats.client.api.DeliverPolicy
  type ReplayPolicy      = io.nats.client.api.ReplayPolicy
  type DiscardPolicy     = io.nats.client.api.DiscardPolicy
  type RetentionPolicy   = io.nats.client.api.RetentionPolicy
  type CompressionOption = io.nats.client.api.CompressionOption
  type PriorityPolicy    = io.nats.client.api.PriorityPolicy
  object AckPolicy {
    val Explicit: AckPolicy = io.nats.client.api.AckPolicy.Explicit
    val All: AckPolicy      = io.nats.client.api.AckPolicy.All
    val None: AckPolicy     = io.nats.client.api.AckPolicy.None
  }

  object DeliverPolicy {
    val All: DeliverPolicy             = io.nats.client.api.DeliverPolicy.All
    val Last: DeliverPolicy            = io.nats.client.api.DeliverPolicy.Last
    val New: DeliverPolicy             = io.nats.client.api.DeliverPolicy.New
    val ByStartSequence: DeliverPolicy = io.nats.client.api.DeliverPolicy.ByStartSequence
    val ByStartTime: DeliverPolicy     = io.nats.client.api.DeliverPolicy.ByStartTime
    val LastPerSubject: DeliverPolicy  = io.nats.client.api.DeliverPolicy.LastPerSubject
  }

  object ReplayPolicy {
    val Instant: ReplayPolicy  = io.nats.client.api.ReplayPolicy.Instant
    val Original: ReplayPolicy = io.nats.client.api.ReplayPolicy.Original
  }

  object DiscardPolicy {
    val Old: DiscardPolicy = io.nats.client.api.DiscardPolicy.Old
    val New: DiscardPolicy = io.nats.client.api.DiscardPolicy.New
  }

  object RetentionPolicy {
    val Limits: RetentionPolicy    = io.nats.client.api.RetentionPolicy.Limits
    val Interest: RetentionPolicy  = io.nats.client.api.RetentionPolicy.Interest
    val WorkQueue: RetentionPolicy = io.nats.client.api.RetentionPolicy.WorkQueue
  }

  object CompressionOption {
    val None: CompressionOption = io.nats.client.api.CompressionOption.None
    val S2: CompressionOption   = io.nats.client.api.CompressionOption.S2
  }

  object PriorityPolicy {
    val None: PriorityPolicy        = io.nats.client.api.PriorityPolicy.None
    val Overflow: PriorityPolicy    = io.nats.client.api.PriorityPolicy.Overflow
    val Prioritized: PriorityPolicy = io.nats.client.api.PriorityPolicy.Prioritized
    val PinnedClient: PriorityPolicy = io.nats.client.api.PriorityPolicy.PinnedClient
  }

  // ---------------------------------------------------------------------------
  // Envelope / ObjectData convenience extensions
  // ---------------------------------------------------------------------------

  /**
   * Strips the [[Envelope]] wrapper from a ZIO effect, returning only the
   * decoded payload and discarding the raw [[NatsMessage]] metadata.
   *
   * {{{
   * Nats.request[Query, Response](subject, query).payload  // ZIO[Nats, NatsError, Response]
   * }}}
   */
  extension [R, E, A](zio: ZIO[R, E, Envelope[A]])
    def payload: ZIO[R, E, A] = zio.map(_.value)

  /**
   * Strips the [[Envelope]] wrapper from every element of a ZStream, emitting
   * only the decoded payloads and discarding the raw [[NatsMessage]] metadata.
   *
   * {{{
   * Nats.subscribe[Event](subject).payload  // ZStream[Nats, NatsError, Event]
   * }}}
   */
  extension [R, E, A](stream: ZStream[R, E, Envelope[A]])
    def payload: ZStream[R, E, A] = stream.map(_.value)

  /**
   * Strips the [[ObjectData]] wrapper from a ZIO effect, returning only the
   * decoded payload and discarding the [[ObjectSummary]] metadata.
   *
   * {{{
   * os.get[MyData]("config.json").payload  // ZIO[Any, NatsError, MyData]
   * }}}
   */
  extension [R, E, A](zio: ZIO[R, E, ObjectData[A]])
    @scala.annotation.targetName("objectDataPayload")
    def payload: ZIO[R, E, A] = zio.map(_.value)

  // --- Config class re-exports (users don't need zio.nats.configuration._) ---
  type StreamConfig = configuration.StreamConfig
  val StreamConfig = configuration.StreamConfig
  type ConsumerConfig = configuration.ConsumerConfig
  val ConsumerConfig = configuration.ConsumerConfig
  type KeyValueConfig = configuration.KeyValueConfig
  val KeyValueConfig = configuration.KeyValueConfig
  type ObjectStoreConfig = configuration.ObjectStoreConfig
  val ObjectStoreConfig = configuration.ObjectStoreConfig
  type OrderedConsumerConfig = configuration.OrderedConsumerConfig
  val OrderedConsumerConfig = configuration.OrderedConsumerConfig
  type RepublishConfig = configuration.RepublishConfig
  val RepublishConfig = configuration.RepublishConfig

  // ---- JetStream re-exports (users don't need zio.nats.jetstream._) ----
  type JetStream           = jetstream.JetStream;           val JetStream           = jetstream.JetStream
  type JetStreamManagement = jetstream.JetStreamManagement; val JetStreamManagement = jetstream.JetStreamManagement
  type Consumer            = jetstream.Consumer
  type OrderedConsumer     = jetstream.OrderedConsumer
  type JetStreamMessage    = jetstream.JetStreamMessage
  type JsEnvelope[A]       = jetstream.JsEnvelope[A];       val JsEnvelope          = jetstream.JsEnvelope
  type PublishAck          = jetstream.PublishAck;           val PublishAck          = jetstream.PublishAck
  type PublishOptions      = jetstream.PublishOptions;       val PublishOptions      = jetstream.PublishOptions
  type JsPublishParams     = jetstream.JsPublishParams;      val JsPublishParams     = jetstream.JsPublishParams
  type FetchOptions        = jetstream.FetchOptions;         val FetchOptions        = jetstream.FetchOptions
  type ConsumeOptions      = jetstream.ConsumeOptions;       val ConsumeOptions      = jetstream.ConsumeOptions
  type StreamSummary       = jetstream.StreamSummary;        val StreamSummary       = jetstream.StreamSummary
  type ConsumerSummary     = jetstream.ConsumerSummary;      val ConsumerSummary     = jetstream.ConsumerSummary
  type PurgeSummary        = jetstream.PurgeSummary;         val PurgeSummary        = jetstream.PurgeSummary
  type ConsumerPauseInfo   = jetstream.ConsumerPauseInfo;    val ConsumerPauseInfo   = jetstream.ConsumerPauseInfo
  type MessageInfo         = jetstream.MessageInfo
  type AccountStatistics   = jetstream.AccountStatistics

  // ---- Key-Value re-exports (users don't need zio.nats.kv._) ----
  type KeyValue             = kv.KeyValue;             val KeyValue             = kv.KeyValue
  type KeyValueManagement   = kv.KeyValueManagement;   val KeyValueManagement   = kv.KeyValueManagement
  type KeyValueEntry        = kv.KeyValueEntry;        val KeyValueEntry        = kv.KeyValueEntry
  type KvEnvelope[A]        = kv.KvEnvelope[A];        val KvEnvelope           = kv.KvEnvelope
  type KvEvent[A]           = kv.KvEvent[A];           val KvEvent              = kv.KvEvent
  type KeyValueOperation    = kv.KeyValueOperation;    val KeyValueOperation    = kv.KeyValueOperation
  type KeyValueWatchOptions = kv.KeyValueWatchOptions; val KeyValueWatchOptions = kv.KeyValueWatchOptions
  type KeyValueBucketStatus = kv.KeyValueBucketStatus; val KeyValueBucketStatus = kv.KeyValueBucketStatus

  // ---- Object Store re-exports (users don't need zio.nats.objectstore._) ----
  type ObjectStore             = objectstore.ObjectStore;             val ObjectStore             = objectstore.ObjectStore
  type ObjectStoreManagement   = objectstore.ObjectStoreManagement;   val ObjectStoreManagement   = objectstore.ObjectStoreManagement
  type ObjectData[A]           = objectstore.ObjectData[A];           val ObjectData              = objectstore.ObjectData
  type ObjectSummary           = objectstore.ObjectSummary;           val ObjectSummary           = objectstore.ObjectSummary
  type ObjectMeta              = objectstore.ObjectMeta;              val ObjectMeta              = objectstore.ObjectMeta
  type ObjectStoreBucketStatus = objectstore.ObjectStoreBucketStatus; val ObjectStoreBucketStatus = objectstore.ObjectStoreBucketStatus
  type ObjectStoreWatchOptions = objectstore.ObjectStoreWatchOptions; val ObjectStoreWatchOptions = objectstore.ObjectStoreWatchOptions
}
