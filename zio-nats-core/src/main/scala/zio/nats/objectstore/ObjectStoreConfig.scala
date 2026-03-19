package zio.nats.objectstore

import io.nats.client.api.ObjectStoreConfiguration as JObjectStoreConfiguration
import zio.Duration
import zio.nats.StorageType

/**
 * Configuration for a NATS Object Store bucket.
 *
 * @param name
 *   Unique bucket name.
 * @param description
 *   Optional human-readable description.
 * @param maxBucketSize
 *   Maximum total bytes for the bucket (-1 = unlimited).
 * @param storageType
 *   File or Memory storage.
 * @param ttl
 *   Default TTL for all objects in the bucket.
 */
case class ObjectStoreConfig(
  name: String,
  description: Option[String] = None,
  maxBucketSize: Long = -1,
  storageType: StorageType = StorageType.File,
  ttl: Option[Duration] = None
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
    config.ttl.foreach(d => builder.ttl(java.time.Duration.ofMillis(d.toMillis)))

    builder.build()
  }
}
