package zio.nats

import io.nats.client.api.{KeyResult as JKeyResult, KeyValueEntry as JKeyValueEntry, KeyValuePurgeOptions, KeyValueWatcher as JKeyValueWatcher}
import io.nats.client.{KeyValue as JKeyValue, KeyValueManagement as JKeyValueManagement, MessageTtl}
import zio.*
import zio.nats.configuration.KeyValueConfig
import zio.stream.*

import scala.jdk.CollectionConverters.*

/** Service for key-value operations on a single NATS KV bucket. */
trait KeyValue {

  def bucketName: String

  // --- Get ---
  def get(key: String): IO[NatsError, Option[KeyValueEntry]]
  def get(key: String, revision: Long): IO[NatsError, Option[KeyValueEntry]]

  // --- Put ---
  /** Encode `value` and store under `key`. Pass `Chunk[Byte]` or `String` for raw/text writes. */
  def put[A: NatsCodec](key: String, value: A): IO[NatsError, Long]

  // --- Conditional writes ---
  /**
   * Put only if the key does not exist (returns revision or fails with
   * JetStreamApiError). Pass an optional per-entry `ttl` (minimum 1 second;
   * the bucket must have been created with a TTL to use this).
   */
  def create[A: NatsCodec](key: String, value: A, ttl: Option[Duration] = None): IO[NatsError, Long]

  /**
   * Compare-and-swap: update only if current revision matches expectedRevision.
   * Pass `Chunk[Byte]` or `String` for raw/text writes.
   */
  def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long]

  // --- Delete / Purge ---
  /**
   * Soft-delete: places a delete marker. History is preserved.
   * Pass `expectedRevision` to guard the delete (fails with JetStreamApiError
   * on mismatch).
   */
  def delete(key: String, expectedRevision: Option[Long] = None): IO[NatsError, Unit]

  /**
   * Hard-purge: removes all history for the key.
   * Pass `expectedRevision` to guard the purge, and/or `markerTtl` to set a
   * TTL on the resulting tombstone marker (bucket must support TTL).
   */
  def purge(
    key: String,
    expectedRevision: Option[Long] = None,
    markerTtl: Option[Duration] = None
  ): IO[NatsError, Unit]

  // --- Watch ---
  /** Stream changes for a specific key with optional watch options. */
  def watch(key: String, options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for multiple keys with optional watch options. */
  def watch(keys: List[String], options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for all keys in the bucket with optional watch options. */
  def watchAll(options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry]

  // --- Delete / tombstone cleanup ---
  /**
   * Remove tombstone (delete/purge marker) entries. Pass `None` to use the
   * server default threshold (30 minutes); pass a negative duration to remove
   * ALL markers regardless of age.
   */
  def purgeDeletes(threshold: Option[Duration] = None): IO[NatsError, Unit]

  // --- Enumeration ---
  /**
   * List keys in the bucket. Pass subject filters (e.g. `List("foo.*")`) to
   * restrict results; omit or pass `Nil` for all keys.
   */
  def keys(filters: List[String] = Nil): IO[NatsError, List[String]]

  /**
   * Stream all keys incrementally. More memory-efficient than `keys` for large
   * buckets — the stream completes when all keys have been delivered.
   * Pass subject filters to restrict results; omit or pass `Nil` for all keys.
   */
  def consumeKeys(filters: List[String] = Nil): ZStream[Any, NatsError, String]

  def history(key: String): IO[NatsError, List[KeyValueEntry]]
  def getStatus: IO[NatsError, KeyValueBucketStatus]
}

/** Service for managing Key-Value buckets. */
trait KeyValueManagement {
  def create(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus]
  def update(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus]
  def delete(bucketName: String): IO[NatsError, Unit]
  def getBucketNames: IO[NatsError, List[String]]
  def getStatus(bucketName: String): IO[NatsError, KeyValueBucketStatus]
  def getStatuses: IO[NatsError, List[KeyValueBucketStatus]]
}

object KeyValue {

  /**
   * Create a KeyValue service bound to a specific bucket name.
   *
   * The bucket must already exist. Use KeyValueManagement.create to create it.
   */
  def bucket(bucketName: String): ZIO[Nats, NatsError, KeyValue] =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO
        .attempt(nats.underlying.keyValue(bucketName))
        .mapBoth(NatsError.fromThrowable, new KeyValueLive(_))
    }

  def put[A: NatsCodec](key: String, value: A): ZIO[KeyValue, NatsError, Long] =
    ZIO.serviceWithZIO[KeyValue](_.put(key, value))

  def create[A: NatsCodec](key: String, value: A, ttl: Option[Duration] = None): ZIO[KeyValue, NatsError, Long] =
    ZIO.serviceWithZIO[KeyValue](_.create(key, value, ttl))

  def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): ZIO[KeyValue, NatsError, Long] =
    ZIO.serviceWithZIO[KeyValue](_.update(key, value, expectedRevision))
}

object KeyValueManagement {

  def create(config: KeyValueConfig): ZIO[KeyValueManagement, NatsError, KeyValueBucketStatus] =
    ZIO.serviceWithZIO[KeyValueManagement](_.create(config))

  def delete(bucketName: String): ZIO[KeyValueManagement, NatsError, Unit] =
    ZIO.serviceWithZIO[KeyValueManagement](_.delete(bucketName))

  val live: ZLayer[Nats, NatsError, KeyValueManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        kvm  <- ZIO
                 .attempt(nats.underlying.keyValueManagement())
                 .mapError(NatsError.fromThrowable)
      } yield new KeyValueManagementLive(kvm)
    }
}

private[nats] final class KeyValueLive(kv: JKeyValue) extends KeyValue {

  override def bucketName: String = kv.getBucketName

  override def get(key: String): IO[NatsError, Option[KeyValueEntry]] =
    ZIO
      .attemptBlocking(Option(kv.get(key)))
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueEntry.fromJava))

  override def get(key: String, revision: Long): IO[NatsError, Option[KeyValueEntry]] =
    ZIO
      .attemptBlocking(Option(kv.get(key, revision)))
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueEntry.fromJava))

  override def put[A: NatsCodec](key: String, value: A): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.put(key, NatsCodec[A].encode(value).toArray)).mapError(NatsError.fromThrowable)

  override def create[A: NatsCodec](key: String, value: A, ttl: Option[Duration] = None): IO[NatsError, Long] = {
    val bytes = NatsCodec[A].encode(value).toArray
    ttl match {
      case None    => ZIO.attemptBlocking(kv.create(key, bytes)).mapError(NatsError.fromThrowable)
      case Some(d) => ZIO.attemptBlocking(kv.create(key, bytes, MessageTtl.seconds(d.toSeconds.toInt))).mapError(NatsError.fromThrowable)
    }
  }

  override def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.update(key, NatsCodec[A].encode(value).toArray, expectedRevision)).mapError(NatsError.fromThrowable)

  override def delete(key: String, expectedRevision: Option[Long] = None): IO[NatsError, Unit] =
    expectedRevision match {
      case None      => ZIO.attemptBlocking(kv.delete(key)).mapError(NatsError.fromThrowable)
      case Some(rev) => ZIO.attemptBlocking(kv.delete(key, rev)).mapError(NatsError.fromThrowable)
    }

  override def purge(
    key: String,
    expectedRevision: Option[Long] = None,
    markerTtl: Option[Duration] = None
  ): IO[NatsError, Unit] =
    (expectedRevision, markerTtl) match {
      case (None,      None)    => ZIO.attemptBlocking(kv.purge(key)).mapError(NatsError.fromThrowable)
      case (Some(rev), None)    => ZIO.attemptBlocking(kv.purge(key, rev)).mapError(NatsError.fromThrowable)
      case (None,      Some(d)) => ZIO.attemptBlocking(kv.purge(key, MessageTtl.seconds(d.toSeconds.toInt))).mapError(NatsError.fromThrowable)
      case (Some(rev), Some(d)) => ZIO.attemptBlocking(kv.purge(key, rev, MessageTtl.seconds(d.toSeconds.toInt))).mapError(NatsError.fromThrowable)
    }

  override def watch(key: String, options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.SingleKey(key), options)

  override def watch(keys: List[String], options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.MultiKey(keys), options)

  override def watchAll(options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.All, options)

  private enum WatchTarget {
    case SingleKey(key: String)
    case MultiKey(keys: List[String])
    case All
  }

  private def watchInternal(target: WatchTarget, options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry] =
    ZStream.asyncScoped[Any, NatsError, KeyValueEntry] { emit =>
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val watcher = new JKeyValueWatcher {
            override def watch(entry: JKeyValueEntry): Unit =
              emit(ZIO.succeed(Chunk.single(KeyValueEntry.fromJava(entry))))
            override def endOfData(): Unit = ()
          }
          val jOpts = KeyValueWatchOptions.toJava(options)
          target match {
            case WatchTarget.SingleKey(k) =>
              options.fromRevision match {
                case Some(rev) => kv.watch(k, watcher, rev, jOpts*)
                case None      => kv.watch(k, watcher, jOpts*)
              }
            case WatchTarget.MultiKey(ks) =>
              options.fromRevision match {
                case Some(rev) => kv.watch(ks.asJava, watcher, rev, jOpts*)
                case None      => kv.watch(ks.asJava, watcher, jOpts*)
              }
            case WatchTarget.All =>
              options.fromRevision match {
                case Some(rev) => kv.watchAll(watcher, rev, jOpts*)
                case None      => kv.watchAll(watcher, jOpts*)
              }
          }
        }.mapError(NatsError.fromThrowable)
      )(sub => ZIO.attemptBlocking(sub.unsubscribe()).ignoreLogged)
    }

  override def purgeDeletes(threshold: Option[Duration] = None): IO[NatsError, Unit] =
    threshold match {
      case None    => ZIO.attemptBlocking(kv.purgeDeletes()).mapError(NatsError.fromThrowable)
      case Some(d) =>
        val opts = KeyValuePurgeOptions.builder().deleteMarkersThreshold(d.toMillis).build()
        ZIO.attemptBlocking(kv.purgeDeletes(opts)).mapError(NatsError.fromThrowable)
    }

  override def keys(filters: List[String] = Nil): IO[NatsError, List[String]] =
    if (filters.isEmpty)
      ZIO.attemptBlocking(kv.keys().asScala.toList).mapError(NatsError.fromThrowable)
    else
      ZIO.attemptBlocking(kv.keys(filters.asJava).asScala.toList).mapError(NatsError.fromThrowable)

  override def consumeKeys(filters: List[String] = Nil): ZStream[Any, NatsError, String] =
    if (filters.isEmpty)
      consumeKeysInternal(ZIO.attemptBlocking(kv.consumeKeys()).mapError(NatsError.fromThrowable))
    else
      consumeKeysInternal(ZIO.attemptBlocking(kv.consumeKeys(filters.asJava)).mapError(NatsError.fromThrowable))

  private def consumeKeysInternal(
    acquireQueue: IO[NatsError, java.util.concurrent.LinkedBlockingQueue[JKeyResult]]
  ): ZStream[Any, NatsError, String] =
    ZStream.unwrap(
      acquireQueue.map { queue =>
        ZStream.repeatZIOOption(
          ZIO.attemptBlocking(queue.take()).mapError(e => Some(NatsError.fromThrowable(e))).flatMap { r =>
            if (r.isDone) ZIO.fail(None)
            else if (r.isException) ZIO.fail(Some(NatsError.fromThrowable(r.getException)))
            else ZIO.succeed(r.getKey)
          }
        )
      }
    )

  override def history(key: String): IO[NatsError, List[KeyValueEntry]] =
    ZIO
      .attemptBlocking(kv.history(key).asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueEntry.fromJava))

  override def getStatus: IO[NatsError, KeyValueBucketStatus] =
    ZIO
      .attemptBlocking(kv.getStatus)
      .mapBoth(NatsError.fromThrowable, KeyValueBucketStatus.fromJava)
}

private[nats] final class KeyValueManagementLive(kvm: JKeyValueManagement) extends KeyValueManagement {

  override def create(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus] =
    ZIO
      .attemptBlocking(kvm.create(config.toJava))
      .mapBoth(NatsError.fromThrowable, KeyValueBucketStatus.fromJava)

  override def update(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus] =
    ZIO
      .attemptBlocking(kvm.update(config.toJava))
      .mapBoth(NatsError.fromThrowable, KeyValueBucketStatus.fromJava)

  override def delete(bucketName: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kvm.delete(bucketName)).mapError(NatsError.fromThrowable)

  override def getBucketNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(kvm.getBucketNames.asScala.toList).mapError(NatsError.fromThrowable)

  override def getStatus(bucketName: String): IO[NatsError, KeyValueBucketStatus] =
    ZIO
      .attemptBlocking(kvm.getStatus(bucketName))
      .mapBoth(NatsError.fromThrowable, KeyValueBucketStatus.fromJava)

  override def getStatuses: IO[NatsError, List[KeyValueBucketStatus]] =
    ZIO
      .attemptBlocking(kvm.getStatuses().asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueBucketStatus.fromJava))
}
