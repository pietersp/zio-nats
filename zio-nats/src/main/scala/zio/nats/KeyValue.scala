package zio.nats

import io.nats.client.api.{KeyValueEntry as JKeyValueEntry, KeyValueWatcher as JKeyValueWatcher}
import io.nats.client.{KeyValue as JKeyValue, KeyValueManagement as JKeyValueManagement}
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
  def put(key: String, value: Chunk[Byte]): IO[NatsError, Long]
  def put(key: String, value: String): IO[NatsError, Long]

  // --- Conditional writes ---
  /** Put if the key does not exist (returns revision or fails with JetStreamApiError). */
  def create(key: String, value: Chunk[Byte]): IO[NatsError, Long]
  /** Compare-and-swap: update only if current revision matches expectedRevision. */
  def update(key: String, value: Chunk[Byte], expectedRevision: Long): IO[NatsError, Long]

  // --- Delete / Purge ---
  /** Soft-delete: places a delete marker. History is preserved. */
  def delete(key: String): IO[NatsError, Unit]
  /** Hard-purge: removes all history for the key. */
  def purge(key: String): IO[NatsError, Unit]

  // --- Watch ---
  /** Stream changes for a specific key. Never completes unless interrupted. */
  def watch(key: String): ZStream[Any, NatsError, KeyValueEntry]
  /** Stream changes for all keys in the bucket. */
  def watchAll: ZStream[Any, NatsError, KeyValueEntry]

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

  /** Create a KeyValue service bound to a specific bucket name.
    *
    * The bucket must already exist. Use KeyValueManagement.create to create it.
    */
  def bucket(bucketName: String): ZIO[Nats, NatsError, KeyValue] =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.attempt(nats.underlying.keyValue(bucketName))
        .mapError(NatsError.fromThrowable)
        .map(new KeyValueLive(_))
    }
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
        kvm  <- ZIO.attempt(nats.underlying.keyValueManagement())
                  .mapError(NatsError.fromThrowable)
      } yield new KeyValueManagementLive(kvm)
    }
}

private[nats] final class KeyValueLive(kv: JKeyValue) extends KeyValue {

  override def bucketName: String = kv.getBucketName

  override def get(key: String): IO[NatsError, Option[KeyValueEntry]] =
    ZIO.attemptBlocking(Option(kv.get(key)))
      .mapError(NatsError.fromThrowable)
      .map(_.map(KeyValueEntry.fromJava))

  override def get(key: String, revision: Long): IO[NatsError, Option[KeyValueEntry]] =
    ZIO.attemptBlocking(Option(kv.get(key, revision)))
      .mapError(NatsError.fromThrowable)
      .map(_.map(KeyValueEntry.fromJava))

  override def put(key: String, value: Chunk[Byte]): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.put(key, value.toArray)).mapError(NatsError.fromThrowable)

  override def put(key: String, value: String): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.put(key, value)).mapError(NatsError.fromThrowable)

  override def create(key: String, value: Chunk[Byte]): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.create(key, value.toArray)).mapError(NatsError.fromThrowable)

  override def update(key: String, value: Chunk[Byte], expectedRevision: Long): IO[NatsError, Long] =
    ZIO.attemptBlocking(kv.update(key, value.toArray, expectedRevision)).mapError(NatsError.fromThrowable)

  override def delete(key: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kv.delete(key)).mapError(NatsError.fromThrowable)

  override def purge(key: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kv.purge(key)).mapError(NatsError.fromThrowable)

  override def watch(key: String): ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(Some(key))

  override def watchAll: ZStream[Any, NatsError, KeyValueEntry] =
    watchInternal(None)

  private def watchInternal(key: Option[String]): ZStream[Any, NatsError, KeyValueEntry] =
    ZStream.unwrapScoped {
      for {
        queue <- ZIO.acquireRelease(Queue.unbounded[KeyValueEntry])(_.shutdown)
        watcher = new JKeyValueWatcher {
          override def watch(entry: JKeyValueEntry): Unit =
            zio.Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe.run(queue.offer(KeyValueEntry.fromJava(entry)))
                .getOrThrowFiberFailure()
            }
          override def endOfData(): Unit = ()
        }
        _ <- ZIO.acquireRelease(
               ZIO.attemptBlocking {
                 key match {
                   case Some(k) => kv.watch(k, watcher)
                   case None    => kv.watchAll(watcher)
                 }
               }.mapError(NatsError.fromThrowable)
             )(sub => ZIO.attemptBlocking(sub.unsubscribe()).ignoreLogged)
      } yield ZStream.fromQueue(queue)
    }

  override def keys: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(kv.keys().asScala.toList).mapError(NatsError.fromThrowable)

  override def history(key: String): IO[NatsError, List[KeyValueEntry]] =
    ZIO.attemptBlocking(kv.history(key).asScala.toList)
      .mapError(NatsError.fromThrowable)
      .map(_.map(KeyValueEntry.fromJava))

  override def getStatus: IO[NatsError, KeyValueBucketStatus] =
    ZIO.attemptBlocking(kv.getStatus).mapError(NatsError.fromThrowable)
      .map(KeyValueBucketStatus.fromJava)
}

private[nats] final class KeyValueManagementLive(kvm: JKeyValueManagement) extends KeyValueManagement {

  override def create(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus] =
    ZIO.attemptBlocking(kvm.create(config.toJava)).mapError(NatsError.fromThrowable)
      .map(KeyValueBucketStatus.fromJava)

  override def update(config: KeyValueConfig): IO[NatsError, KeyValueBucketStatus] =
    ZIO.attemptBlocking(kvm.update(config.toJava)).mapError(NatsError.fromThrowable)
      .map(KeyValueBucketStatus.fromJava)

  override def delete(bucketName: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(kvm.delete(bucketName)).mapError(NatsError.fromThrowable)

  override def getBucketNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(kvm.getBucketNames.asScala.toList).mapError(NatsError.fromThrowable)

  override def getStatus(bucketName: String): IO[NatsError, KeyValueBucketStatus] =
    ZIO.attemptBlocking(kvm.getStatus(bucketName)).mapError(NatsError.fromThrowable)
      .map(KeyValueBucketStatus.fromJava)
}
