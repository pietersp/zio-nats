package zio

/** Package-level conveniences for zio-nats.
  *
  * Import zio.nats._ to get all services plus these helpers.
  */
package object nats {

  /** Type alias: a ZIO effect that requires the core Nats service. */
  type NatsIO[+A] = ZIO[Nats, NatsError, A]

  /** Type alias: a ZIO effect that requires JetStream. */
  type JetStreamIO[+A] = ZIO[JetStream, NatsError, A]

  /** Implicit conversion: String -> Chunk[Byte] (UTF-8). */
  implicit class StringOps(private val s: String) extends AnyVal {
    /** Encode this string as a UTF-8 byte Chunk suitable for NATS publish. */
    def toNatsData: Chunk[Byte] =
      Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }

  // ---------------------------------------------------------------------------
  // Enum type aliases — the companion objects below provide value access so
  // that `StorageType.Memory` works without any io.nats.client.* imports.
  // ---------------------------------------------------------------------------

  type StorageType       = io.nats.client.api.StorageType
  type AckPolicy         = io.nats.client.api.AckPolicy
  type DeliverPolicy     = io.nats.client.api.DeliverPolicy
  type ReplayPolicy      = io.nats.client.api.ReplayPolicy
  type DiscardPolicy     = io.nats.client.api.DiscardPolicy
  type RetentionPolicy   = io.nats.client.api.RetentionPolicy
  type CompressionOption = io.nats.client.api.CompressionOption
  type KeyValueOperation = io.nats.client.api.KeyValueOperation

  object StorageType {
    val File:   StorageType = io.nats.client.api.StorageType.File
    val Memory: StorageType = io.nats.client.api.StorageType.Memory
  }

  object AckPolicy {
    val Explicit: AckPolicy = io.nats.client.api.AckPolicy.Explicit
    val All:      AckPolicy = io.nats.client.api.AckPolicy.All
    val None:     AckPolicy = io.nats.client.api.AckPolicy.None
  }

  object DeliverPolicy {
    val All:              DeliverPolicy = io.nats.client.api.DeliverPolicy.All
    val Last:             DeliverPolicy = io.nats.client.api.DeliverPolicy.Last
    val New:              DeliverPolicy = io.nats.client.api.DeliverPolicy.New
    val ByStartSequence:  DeliverPolicy = io.nats.client.api.DeliverPolicy.ByStartSequence
    val ByStartTime:      DeliverPolicy = io.nats.client.api.DeliverPolicy.ByStartTime
    val LastPerSubject:   DeliverPolicy = io.nats.client.api.DeliverPolicy.LastPerSubject
  }

  object ReplayPolicy {
    val Instant:  ReplayPolicy = io.nats.client.api.ReplayPolicy.Instant
    val Original: ReplayPolicy = io.nats.client.api.ReplayPolicy.Original
  }

  object DiscardPolicy {
    val Old: DiscardPolicy = io.nats.client.api.DiscardPolicy.Old
    val New: DiscardPolicy = io.nats.client.api.DiscardPolicy.New
  }

  object RetentionPolicy {
    val Limits:    RetentionPolicy = io.nats.client.api.RetentionPolicy.Limits
    val Interest:  RetentionPolicy = io.nats.client.api.RetentionPolicy.Interest
    val WorkQueue: RetentionPolicy = io.nats.client.api.RetentionPolicy.WorkQueue
  }

  object CompressionOption {
    val None: CompressionOption = io.nats.client.api.CompressionOption.None
    val S2:   CompressionOption = io.nats.client.api.CompressionOption.S2
  }

  object KeyValueOperation {
    val PUT:    KeyValueOperation = io.nats.client.api.KeyValueOperation.PUT
    val DELETE: KeyValueOperation = io.nats.client.api.KeyValueOperation.DELETE
    val PURGE:  KeyValueOperation = io.nats.client.api.KeyValueOperation.PURGE
  }

  // --- Subject re-export (import zio.nats._ is all you need) ---
  type Subject = subject.Subject
  val  Subject = subject.Subject

  // --- Config class re-exports (users don't need zio.nats.configuration._) ---
  type StreamConfig      = configuration.StreamConfig
  val  StreamConfig      = configuration.StreamConfig
  type ConsumerConfig    = configuration.ConsumerConfig
  val  ConsumerConfig    = configuration.ConsumerConfig
  type KeyValueConfig    = configuration.KeyValueConfig
  val  KeyValueConfig    = configuration.KeyValueConfig
  type ObjectStoreConfig = configuration.ObjectStoreConfig
  val  ObjectStoreConfig = configuration.ObjectStoreConfig
}
