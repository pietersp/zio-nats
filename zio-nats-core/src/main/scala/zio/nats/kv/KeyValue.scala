package zio.nats.kv

import io.nats.client.api.{
  KeyResult as JKeyResult,
  KeyValueEntry as JKeyValueEntry,
  KeyValuePurgeOptions,
  KeyValueWatcher as JKeyValueWatcher
}
import io.nats.client.{KeyValue as JKeyValue, KeyValueManagement as JKeyValueManagement, MessageTtl}
import zio.*
import zio.nats.{Nats, NatsCodec, NatsError}
import zio.stream.*

import scala.jdk.CollectionConverters.*

/**
 * Service for key-value operations on a single NATS KV bucket.
 *
 * NATS KV is built on JetStream and provides compare-and-swap writes,
 * revisioned history, and change-watch streams.
 *
 * All read operations are generic `[A: NatsCodec]` and return [[KvEnvelope]]s
 * that bundle the decoded value with server-side metadata (key, revision,
 * operation, and bucket name). Pass `Chunk[Byte]` as `A` to skip decoding and
 * receive raw bytes.
 *
 * Obtain an instance via [[KeyValue.bucket]] (requires a [[zio.nats.Nats]]
 * connection in scope). Use [[KeyValueManagement]] to create or delete buckets.
 *
 * ==Example==
 * {{{
 * for {
 *   kv  <- KeyValue.bucket("settings")
 *   _   <- kv.put("timeout", "30s")
 *   env <- kv.get[String]("timeout")
 * } yield env.map(_.value)  // Some("30s")
 * }}}
 */
trait KeyValue {

  /** The name of the KV bucket this service is bound to. */
  def bucketName: String

  // --- Get ---

  /**
   * Decode the value for `key` as `A`. Returns [[None]] if the key does not
   * exist or its current state is a delete or purge marker.
   *
   * @param revision
   *   when [[Some]], retrieves the entry at that specific stream revision; when
   *   [[None]] (default) retrieves the latest value.
   */
  def get[A: NatsCodec](key: String, revision: Option[Long] = None): IO[NatsError, Option[KvEnvelope[A]]]

  // --- Put ---

  /**
   * Encode `value` and store under `key`. Pass `Chunk[Byte]` or `String` for
   * raw/text writes.
   */
  def put[A: NatsCodec](key: String, value: A): IO[NatsError, Long]

  // --- Conditional writes ---

  /**
   * Put only if the key does not exist (returns revision or fails with
   * JetStreamApiError). Pass an optional per-entry `ttl` (minimum 1 second; the
   * bucket must have been created with a TTL to use this).
   */
  def create[A: NatsCodec](key: String, value: A, ttl: Option[Duration] = None): IO[NatsError, Long]

  /**
   * Compare-and-swap: update only if current revision matches expectedRevision.
   * Fails with JetStreamApiError on mismatch.
   */
  def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long]

  // --- Delete / Purge ---

  /**
   * Soft-delete: places a delete marker. History is preserved. Pass
   * `expectedRevision` to guard the delete (fails with JetStreamApiError on
   * mismatch).
   */
  def delete(key: String, expectedRevision: Option[Long] = None): IO[NatsError, Unit]

  /**
   * Hard-purge: removes all history for the key. Pass `expectedRevision` to
   * guard the purge, and/or `markerTtl` to set a TTL on the resulting tombstone
   * marker (bucket must support TTL).
   */
  def purge(
    key: String,
    expectedRevision: Option[Long] = None,
    markerTtl: Option[Duration] = None
  ): IO[NatsError, Unit]

  // --- Watch ---

  /**
   * Stream events for one or more keys as [[KvEvent]] values.
   *
   * Pass a single-element list to watch one key, or multiple keys to watch all
   * of them in a single subscription. The stream never completes on its own —
   * interrupt it to stop watching.
   *
   * Emits [[KvEvent.Put]] for writes, [[KvEvent.Delete]] for soft-deletes, and
   * [[KvEvent.Purge]] for purges. Set [[KeyValueWatchOptions.ignoreDeletes]] to
   * suppress Delete and Purge events at the server side.
   */
  def watch[A: NatsCodec](
    keys: List[String],
    options: KeyValueWatchOptions = KeyValueWatchOptions.default
  ): ZStream[Any, NatsError, KvEvent[A]]

  /**
   * Stream events for all keys in the bucket as [[KvEvent]] values.
   *
   * Emits [[KvEvent.Put]] for writes, [[KvEvent.Delete]] for soft-deletes, and
   * [[KvEvent.Purge]] for purges. Set [[KeyValueWatchOptions.ignoreDeletes]] to
   * suppress Delete and Purge events at the server side.
   */
  def watchAll[A: NatsCodec](
    options: KeyValueWatchOptions = KeyValueWatchOptions.default
  ): ZStream[Any, NatsError, KvEvent[A]]

  // --- History ---

  /**
   * Return decoded values from the revision history of `key`, oldest to newest.
   * Delete and purge marker entries have no decodable value and are omitted;
   * use [[watch]] with [[KeyValueWatchOptions.includeHistory]] to observe them.
   */
  def history[A: NatsCodec](key: String): IO[NatsError, List[KvEnvelope[A]]]

  // --- Delete / tombstone cleanup ---

  /**
   * Remove tombstone (delete/purge marker) entries. Pass [[None]] to use the
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
   * buckets — the stream completes when all keys have been delivered. Pass
   * subject filters to restrict results; omit or pass `Nil` for all keys.
   */
  def consumeKeys(filters: List[String] = Nil): ZStream[Any, NatsError, String]

  /** Return the current status and configuration of this bucket. */
  def getStatus: IO[NatsError, KeyValueBucketStatus]
}

/**
 * Service for managing Key-Value buckets.
 *
 * Provides administrative operations to create, update, and delete KV buckets.
 * Obtain an instance via [[KeyValueManagement.live]] (requires
 * [[zio.nats.Nats]] in scope).
 */
trait KeyValueManagement {

  /** Create a new KV bucket with the given configuration. */
  def create(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus]

  /** Update an existing KV bucket's configuration. */
  def update(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus]

  /** Delete a KV bucket and all its entries. */
  def delete(bucketName: String): IO[NatsError, Unit]

  /** List the names of all KV buckets on this server. */
  def getBucketNames: IO[NatsError, List[String]]

  /** Get the current status and configuration for a named bucket. */
  def getStatus(bucketName: String): IO[NatsError, KeyValueBucketStatus]

  /** Get status information for all KV buckets. */
  def getStatuses: IO[NatsError, List[KeyValueBucketStatus]]
}

object KeyValue {

  /**
   * Create a [[KeyValue]] service bound to a specific bucket name.
   *
   * The bucket must already exist. Use [[KeyValueManagement.create]] to create
   * it.
   */
  def bucket(bucketName: String): ZIO[Nats, NatsError, KeyValue] =
    ZIO.serviceWithZIO[Nats] { nats =>
      nats.underlying.flatMap(conn =>
        ZIO.attempt(conn.keyValue(bucketName)).mapBoth(NatsError.fromThrowable, new KeyValueLive(_))
      )
    }

  /**
   * A [[ZLayer]] that opens an existing KV bucket and provides a [[KeyValue]]
   * service backed by it.
   *
   * This is the idiomatic ZIO way to wire a KV bucket into an application's
   * dependency graph. For programmatic use inside a for-comprehension, use
   * [[bucket]] instead.
   *
   * @param bucketName
   *   The name of the KV bucket to open.
   */
  def live(bucketName: String): ZLayer[Nats, NatsError, KeyValue] =
    ZLayer {
      ZIO.serviceWithZIO[Nats] { nats =>
        nats.underlying.flatMap(conn =>
          ZIO.attempt(conn.keyValue(bucketName)).mapBoth(NatsError.fromThrowable, new KeyValueLive(_))
        )
      }
    }

}

object KeyValueManagement {

  val live: ZLayer[Nats, NatsError, KeyValueManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        conn <- nats.underlying
        kvm  <- ZIO.attempt(conn.keyValueManagement()).mapError(NatsError.fromThrowable)
      } yield new KeyValueManagementLive(kvm)
    }
}

private[nats] final class KeyValueLive(kv: JKeyValue) extends KeyValue {

  override def bucketName: String = kv.getBucketName

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def decodeOpt[A: NatsCodec](opt: Option[KeyValueEntry]): IO[NatsError, Option[KvEnvelope[A]]] =
    opt match {
      case None                                            => ZIO.none
      case Some(e) if e.operation != KeyValueOperation.Put => ZIO.none
      case Some(e)                                         =>
        ZIO
          .fromEither(e.decode[A])
          .mapBoth(err => NatsError.DecodingError(err.message, err), v => Some(KvEnvelope(v, e)))
    }

  private def toEvents[A: NatsCodec](
    raw: ZStream[Any, NatsError, KeyValueEntry]
  ): ZStream[Any, NatsError, KvEvent[A]] =
    raw.mapZIO { e =>
      e.operation match {
        case KeyValueOperation.Put =>
          ZIO
            .fromEither(e.decode[A])
            .mapBoth(err => NatsError.DecodingError(err.message, err), v => KvEvent.Put(KvEnvelope(v, e)))
        case KeyValueOperation.Delete =>
          ZIO.succeed(KvEvent.Delete(e))
        case KeyValueOperation.Purge =>
          ZIO.succeed(KvEvent.Purge(e))
      }
    }

  // ---------------------------------------------------------------------------
  // Get
  // ---------------------------------------------------------------------------

  override def get[A: NatsCodec](key: String, revision: Option[Long] = None): IO[NatsError, Option[KvEnvelope[A]]] =
    ZIO
      .attemptBlocking(revision match {
        case None      => Option(kv.get(key))
        case Some(rev) => Option(kv.get(key, rev))
      })
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueEntry.fromJava))
      .flatMap(decodeOpt[A])

  // ---------------------------------------------------------------------------
  // Put / Create / Update
  // ---------------------------------------------------------------------------

  /**
   * Convert a `Duration` to a jnats `MessageTtl`. NATS KV supports a maximum
   * TTL of approximately 68 years (Int.MaxValue seconds). Longer durations are
   * clamped to this maximum.
   */
  private def toMessageTtl(d: Duration): MessageTtl =
    MessageTtl.seconds(d.toSeconds.min(Int.MaxValue).toInt)

  private def encode[A: NatsCodec](key: String, value: A): IO[NatsError, Array[Byte]] =
    ZIO
      .attempt(NatsCodec[A].encode(value).toArray)
      .mapError(e => NatsError.SerializationError(s"Failed to encode value for key '$key': ${e.toString}", e))

  override def put[A: NatsCodec](key: String, value: A): IO[NatsError, Long] =
    encode(key, value).flatMap(bytes => ZIO.attemptBlocking(kv.put(key, bytes)).mapError(NatsError.fromThrowable))

  override def create[A: NatsCodec](key: String, value: A, ttl: Option[Duration] = None): IO[NatsError, Long] =
    encode(key, value).flatMap { bytes =>
      ttl match {
        case None    => ZIO.attemptBlocking(kv.create(key, bytes)).mapError(NatsError.fromThrowable)
        case Some(d) =>
          ZIO
            .attemptBlocking(kv.create(key, bytes, toMessageTtl(d)))
            .mapError(NatsError.fromThrowable)
      }
    }

  override def update[A: NatsCodec](key: String, value: A, expectedRevision: Long): IO[NatsError, Long] =
    encode(key, value).flatMap(bytes =>
      ZIO.attemptBlocking(kv.update(key, bytes, expectedRevision)).mapError(NatsError.fromThrowable)
    )

  // ---------------------------------------------------------------------------
  // Delete / Purge
  // ---------------------------------------------------------------------------

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
      case (None, None)      => ZIO.attemptBlocking(kv.purge(key)).mapError(NatsError.fromThrowable)
      case (Some(rev), None) => ZIO.attemptBlocking(kv.purge(key, rev)).mapError(NatsError.fromThrowable)
      case (None, Some(d))   =>
        ZIO.attemptBlocking(kv.purge(key, toMessageTtl(d))).mapError(NatsError.fromThrowable)
      case (Some(rev), Some(d)) =>
        ZIO.attemptBlocking(kv.purge(key, rev, toMessageTtl(d))).mapError(NatsError.fromThrowable)
    }

  // ---------------------------------------------------------------------------
  // Watch
  // ---------------------------------------------------------------------------

  override def watch[A: NatsCodec](
    keys: List[String],
    options: KeyValueWatchOptions = KeyValueWatchOptions.default
  ): ZStream[Any, NatsError, KvEvent[A]] =
    toEvents(watchInternal(WatchTarget.Keys(keys), options))

  override def watchAll[A: NatsCodec](
    options: KeyValueWatchOptions = KeyValueWatchOptions.default
  ): ZStream[Any, NatsError, KvEvent[A]] =
    toEvents(watchInternal(WatchTarget.All, options))

  private enum WatchTarget {
    case Keys(keys: List[String])
    case All
  }

  private def watchInternal(
    target: WatchTarget,
    options: KeyValueWatchOptions
  ): ZStream[Any, NatsError, KeyValueEntry] =
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
            case WatchTarget.Keys(ks) =>
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

  // ---------------------------------------------------------------------------
  // History
  // ---------------------------------------------------------------------------

  override def history[A: NatsCodec](key: String): IO[NatsError, List[KvEnvelope[A]]] =
    ZIO
      .attemptBlocking(kv.history(key).asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(KeyValueEntry.fromJava))
      .flatMap { entries =>
        ZIO.foreach(entries.filter(_.operation == KeyValueOperation.Put)) { e =>
          ZIO
            .fromEither(e.decode[A])
            .mapBoth(err => NatsError.DecodingError(err.message, err), KvEnvelope(_, e))
        }
      }

  // ---------------------------------------------------------------------------
  // Tombstone / keys / status
  // ---------------------------------------------------------------------------

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
