package zio.nats.kv

import io.nats.client.api.{External, Mirror, Republish, Source, KeyValueConfiguration as JKeyValueConfiguration}
import zio.Duration
import zio.nats.StorageType
import zio.nats.jetstream.{ExternalConfig, MirrorConfig, SourceConfig}

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// RepublishConfig
// ---------------------------------------------------------------------------

/**
 * Republish configuration — re-publishes messages from a KV bucket to another
 * subject after they are stored.
 *
 * @param source
 *   Subject filter matching the published subject.
 * @param destination
 *   Subject template for the re-published message.
 * @param headersOnly
 *   If true, re-publish only headers (no body).
 */
case class RepublishConfig(
  source: String,
  destination: String,
  headersOnly: Boolean = false
)

// ---------------------------------------------------------------------------
// KeyValueConfig
// ---------------------------------------------------------------------------

/**
 * Configuration for a NATS KV bucket.
 *
 * @param name
 *   Unique bucket name.
 * @param description
 *   Optional human-readable description.
 * @param maxValueSize
 *   Maximum size of a single value in bytes (-1 = unlimited). Must fit in a 32-bit signed integer.
 * @param maxBucketSize
 *   Maximum total bytes for the bucket (-1 = unlimited).
 * @param maxHistoryPerKey
 *   Maximum revisions to keep per key (-1 = unlimited).
 * @param storageType
 *   File or Memory storage.
 * @param compression
 *   Enable server-side compression.
 * @param numberOfReplicas
 *   Number of server replicas (default: 1).
 * @param ttl
 *   Default TTL for all entries (server must support per-message TTL).
 * @param limitMarkerTtl
 *   TTL applied to delete/purge tombstone markers.
 * @param mirror
 *   Mirror another KV bucket into this one.
 * @param sources
 *   Aggregate entries from other KV buckets.
 * @param republish
 *   Re-publish stored entries to another subject.
 */
case class KeyValueConfig(
  name: String,
  description: Option[String] = None,
  maxValueSize: Int = -1,
  maxBucketSize: Long = -1,
  maxHistoryPerKey: Int = -1,
  storageType: StorageType = StorageType.File,
  compression: Boolean = false,
  numberOfReplicas: Int = 1,
  ttl: Option[Duration] = None,
  limitMarkerTtl: Option[Duration] = None,
  mirror: Option[MirrorConfig] = None,
  sources: List[SourceConfig] = Nil,
  republish: Option[RepublishConfig] = None
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
    if (config.maxValueSize > 0) builder.maximumValueSize(config.maxValueSize)
    if (config.maxBucketSize > 0) builder.maxBucketSize(config.maxBucketSize)
    if (config.maxHistoryPerKey > 0) builder.maxHistoryPerKey(config.maxHistoryPerKey)
    config.ttl.foreach(d => builder.ttl(java.time.Duration.ofMillis(d.toMillis)))
    config.limitMarkerTtl.foreach(d => builder.limitMarker(java.time.Duration.ofMillis(d.toMillis)))

    config.mirror.foreach { m =>
      val mb = Mirror.builder().name(m.name)
      m.filterSubject.foreach(s => mb.filterSubject(s))
      m.external.foreach { ext =>
        mb.external(External.builder().api(ext.api).deliver(ext.deliver).build())
      }
      builder.mirror(mb.build())
    }

    config.sources.foreach { s =>
      val sb = Source.builder().name(s.name)
      s.filterSubject.foreach(f => sb.filterSubject(f))
      s.external.foreach { ext =>
        sb.external(External.builder().api(ext.api).deliver(ext.deliver).build())
      }
      builder.sources(sb.build())
    }

    config.republish.foreach { r =>
      builder.republish(
        Republish.builder().source(r.source).destination(r.destination).headersOnly(r.headersOnly).build()
      )
    }

    builder.build()
  }
}
