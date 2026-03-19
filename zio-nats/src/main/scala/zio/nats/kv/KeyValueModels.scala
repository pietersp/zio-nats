package zio.nats.kv

import io.nats.client.api.{
  KeyValueEntry as JKeyValueEntry,
  KeyValueStatus as JKeyValueStatus,
  KeyValueWatchOption as JKeyValueWatchOption
}
import zio.*
import zio.nats.{NatsCodec, NatsDecodeError, StorageType}

// ---------------------------------------------------------------------------
// KeyValueOperation
// ---------------------------------------------------------------------------

/**
 * The type of operation that produced a [[KeyValueEntry]].
 *
 *   - `Put` — a value was written.
 *   - `Delete` — a soft-delete marker was placed (history preserved).
 *   - `Purge` — all history for the key was erased (tombstone written).
 */
enum KeyValueOperation { case Put, Delete, Purge }

object KeyValueOperation {
  private[nats] def fromJava(op: io.nats.client.api.KeyValueOperation): KeyValueOperation = op match {
    case io.nats.client.api.KeyValueOperation.PUT    => Put
    case io.nats.client.api.KeyValueOperation.DELETE => Delete
    case io.nats.client.api.KeyValueOperation.PURGE  => Purge
  }
}

// ---------------------------------------------------------------------------
// KeyValueEntry
// ---------------------------------------------------------------------------

/**
 * Raw metadata for a single entry in a NATS KV bucket.
 *
 * Embedded in [[KvEnvelope]] to give callers access to revision, operation, and
 * other server-side metadata alongside the decoded value.
 *
 * @param key
 *   The entry key.
 * @param value
 *   Raw payload bytes. Empty for delete/purge markers.
 * @param revision
 *   The stream sequence number (monotonically increasing).
 * @param operation
 *   Whether this entry is a [[KeyValueOperation.Put]],
 *   [[KeyValueOperation.Delete]], or [[KeyValueOperation.Purge]] marker.
 * @param bucketName
 *   The bucket this entry belongs to.
 */
final case class KeyValueEntry(
  key: String,
  value: Chunk[Byte],
  revision: Long,
  operation: KeyValueOperation,
  bucketName: String
) {
  private[nats] def valueAsString: String                            = new String(value.toArray, java.nio.charset.StandardCharsets.UTF_8)
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

// ---------------------------------------------------------------------------
// KvEnvelope
// ---------------------------------------------------------------------------

/**
 * A decoded Key-Value entry paired with its server-side [[KeyValueEntry]]
 * metadata.
 *
 * Returned by [[KeyValue.get]] and [[KeyValue.history]], which only ever yield
 * Put entries. Pass `Chunk[Byte]` as `A` to skip decoding and receive raw
 * bytes.
 *
 * @param value
 *   The decoded payload.
 * @param entry
 *   The raw [[KeyValueEntry]] containing metadata and raw bytes.
 */
final case class KvEnvelope[+A](value: A, entry: KeyValueEntry) {

  /** The entry key. */
  def key: String = entry.key

  /** The stream sequence revision of this entry (monotonically increasing). */
  def revision: Long = entry.revision

  /** The bucket this entry belongs to. */
  def bucketName: String = entry.bucketName
}

// ---------------------------------------------------------------------------
// KvEvent
// ---------------------------------------------------------------------------

/**
 * An event emitted by a [[KeyValue.watch]] or [[KeyValue.watchAll]] stream.
 *
 *   - [[KvEvent.Put]] carries a decoded value for each successful write.
 *   - [[KvEvent.Delete]] signals that the key was soft-deleted (history
 *     preserved).
 *   - [[KvEvent.Purge]] signals that all history for the key was erased.
 *
 * All three cases expose [[key]], [[revision]], and [[bucketName]].
 *
 * Set [[KeyValueWatchOptions.ignoreDeletes]] to suppress [[KvEvent.Delete]] and
 * [[KvEvent.Purge]] at the server side when you only care about
 * [[KvEvent.Put]].
 *
 * {{{
 * kv.watch[UserProfile](List("user.42")).map {
 *   case KvEvent.Put(env)    => handleUpdate(env.value)
 *   case KvEvent.Delete(e)   => handleDelete(e.key)
 *   case KvEvent.Purge(e)    => handlePurge(e.key)
 * }
 * }}}
 */
enum KvEvent[+A] {

  /** A value was stored under the key. */
  case Put(envelope: KvEnvelope[A])

  /** The key was soft-deleted; its history is still accessible. */
  case Delete(entry: KeyValueEntry)

  /** All history for the key was purged. */
  case Purge(entry: KeyValueEntry)

  /** The key this event refers to. */
  def key: String = this match {
    case Put(env)  => env.key
    case Delete(e) => e.key
    case Purge(e)  => e.key
  }

  /** The stream revision at which this event occurred. */
  def revision: Long = this match {
    case Put(env)  => env.revision
    case Delete(e) => e.revision
    case Purge(e)  => e.revision
  }

  /** The bucket this event belongs to. */
  def bucketName: String = this match {
    case Put(env)  => env.bucketName
    case Delete(e) => e.bucketName
    case Purge(e)  => e.bucketName
  }
}

// ---------------------------------------------------------------------------
// KeyValueWatchOptions
// ---------------------------------------------------------------------------

/**
 * Options that control which entries a KV watch delivers.
 *
 * @param ignoreDeletes
 *   Suppress [[KvEvent.Delete]] and [[KvEvent.Purge]] events. When `true` the
 *   server does not transmit these entries, so the watch stream emits only
 *   [[KvEvent.Put]] events.
 * @param metaOnly
 *   Receive only metadata; omit value bytes (default: include values).
 * @param includeHistory
 *   Start from the first entry per key instead of the last (default: last per
 *   key).
 * @param updatesOnly
 *   Start only from new entries written after the watch begins (default: last
 *   per key).
 * @param fromRevision
 *   Resume from a specific stream revision (overrides the deliver-policy flags
 *   above).
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
// KeyValueBucketStatus
// ---------------------------------------------------------------------------

/**
 * Current status and configuration of a NATS KV bucket.
 *
 * @param bucketName
 *   The bucket name.
 * @param description
 *   Optional description.
 * @param entryCount
 *   Number of live entries in the bucket.
 * @param byteCount
 *   Total bytes stored in the bucket.
 * @param maxHistoryPerKey
 *   Maximum revisions to keep per key (-1 = unlimited).
 * @param maxBucketSize
 *   Maximum total bytes for the bucket (-1 = unlimited).
 * @param storageType
 *   File or Memory storage.
 * @param replicas
 *   Number of server replicas.
 * @param isCompressed
 *   Whether values are compressed on the server.
 * @param ttl
 *   Default TTL for entries, if configured.
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
