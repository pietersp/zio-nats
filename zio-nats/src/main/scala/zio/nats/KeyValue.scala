package zio.nats

import io.nats.client.api.{KeyValueEntry as JKeyValueEntry, KeyValueWatcher as JKeyValueWatcher}
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
   * Put if the key does not exist (returns revision or fails with
   * JetStreamApiError). Pass `Chunk[Byte]` or `String` for raw/text writes.
   */
  def create[A: NatsCodec](key: String, value: A): IO[NatsError, Long]

  /**
   * Put if the key does not exist, with a per-entry TTL (minimum 1 second).
   * The bucket must have been created with a TTL to use this. Returns revision
   * or fails with JetStreamApiError.
   */
  def create[A: NatsCodec](key: String, value: A, ttl: Duration): IO[NatsError, Long]

  /**
   * Compare-and-swap: update only if current revision matches expectedRevision.
   * Pass `Chunk[Byte]` or `String` for raw/text writes.
   */
  def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long]

  // --- Delete / Purge ---
  /** Soft-delete: places a delete marker. History is preserved. */
  def delete(key: String): IO[NatsError, Unit]

  /** Hard-purge: removes all history for the key. */
  def purge(key: String): IO[NatsError, Unit]

  // --- Watch ---
  /** Stream changes for a specific key. Never completes unless interrupted. */
  def watch(key: String): ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for a specific key with watch options (filtering, start position). */
  def watch(key: String, options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for multiple keys with watch options. */
  def watch(keys: List[String], options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for all keys in the bucket. */
  def watchAll: ZStream[Any, NatsError, KeyValueEntry]

  /** Stream changes for all keys in the bucket with watch options (filtering, start position). */
  def watchAll(options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry]

  // --- Enumeration ---
  def keys: IO[NatsError, List[String]]
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

  def create[A: NatsCodec](key: String, value: A): ZIO[KeyValue, NatsError, Long] =
    ZIO.serviceWithZIO[KeyValue](_.create(key, value))

  def create[A: NatsCodec](key: String, value: A, ttl: Duration): ZIO[KeyValue, NatsError, Long] =
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

  override def create[A: NatsCodec](key: String, value: A): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.create(key, NatsCodec[A].encode(value).toArray)).mapError(NatsError.fromThrowable)

  override def create[A: NatsCodec](key: String, value: A, ttl: Duration): IO[NatsError, Long] =
    ZIO
      .attemptBlocking(kv.create(key, NatsCodec[A].encode(value).toArray, MessageTtl.seconds(ttl.toSeconds.toInt)))
      .mapError(NatsError.fromThrowable)

  override def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.update(key, NatsCodec[A].encode(value).toArray, expectedRevision)).mapError(NatsError.fromThrowable)

  override def delete(key: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kv.delete(key)).mapError(NatsError.fromThrowable)

  override def purge(key: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kv.purge(key)).mapError(NatsError.fromThrowable)

  override def watch(key: String): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.SingleKey(key), KeyValueWatchOptions.default)

  override def watch(key: String, options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.SingleKey(key), options)

  override def watch(keys: List[String], options: KeyValueWatchOptions = KeyValueWatchOptions.default): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.MultiKey(keys), options)

  override def watchAll: ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.All, KeyValueWatchOptions.default)

  override def watchAll(options: KeyValueWatchOptions): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(WatchTarget.All, options)

  private sealed trait WatchTarget
  private object WatchTarget {
    case class SingleKey(key: String)      extends WatchTarget
    case class MultiKey(keys: List[String]) extends WatchTarget
    case object All                         extends WatchTarget
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

  override def keys: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(kv.keys().asScala.toList).mapError(NatsError.fromThrowable)

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
}
