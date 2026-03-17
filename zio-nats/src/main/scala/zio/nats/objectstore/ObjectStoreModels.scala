package zio.nats.objectstore

import io.nats.client.api.{ObjectInfo as JObjectInfo, ObjectStoreStatus as JObjectStoreStatus, ObjectStoreWatchOption as JObjectStoreWatchOption}
import zio.Chunk
import zio.nats.{Headers, StorageType}

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// ObjectMeta
// ---------------------------------------------------------------------------

/**
 * Custom metadata for an Object Store entry.
 *
 * Pass to [[ObjectStore.put]] or [[ObjectStore.putStream]] to attach a
 * description or headers alongside the stored object.
 *
 * @param name        The object name (unique within the bucket).
 * @param description Optional human-readable description.
 * @param headers     Optional NATS headers to store with the object.
 */
final case class ObjectMeta(
  name: String,
  description: Option[String] = None,
  headers: Headers = Headers.empty
) {
  private[nats] def toJava: io.nats.client.api.ObjectMeta = {
    val b = io.nats.client.api.ObjectMeta.builder(name)
    description.foreach(b.description)
    if (headers.nonEmpty) {
      val jHeaders = new io.nats.client.impl.Headers()
      headers.values.foreach { case (key, values) =>
        jHeaders.add(key, values.toList.asJava)
      }
      b.headers(jHeaders)
    }
    b.build()
  }
}

// ---------------------------------------------------------------------------
// ObjectData
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

// ---------------------------------------------------------------------------
// ObjectSummary
// ---------------------------------------------------------------------------

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
// ObjectStoreWatchOptions
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
// ObjectStoreBucketStatus
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
