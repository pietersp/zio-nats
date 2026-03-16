package zio.nats

import io.nats.client.api.{ObjectMeta, ObjectInfo as JObjectInfo, ObjectStoreWatcher as JObjectStoreWatcher}
import io.nats.client.{ObjectStore as JObjectStore, ObjectStoreManagement as JObjectStoreManagement}
import zio.*
import zio.nats.configuration.ObjectStoreConfig
import zio.stream.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

/** Service for object store operations on a single NATS OS bucket. */
trait ObjectStore {

  def bucketName: String

  /** Store raw bytes under objectName. Returns ObjectSummary with size, digest, etc. */
  def put(objectName: String, data: Chunk[Byte]): IO[NatsError, ObjectSummary]

  /** Store bytes with custom metadata. */
  def put(meta: ObjectMeta, data: Chunk[Byte]): IO[NatsError, ObjectSummary]

  /** Retrieve the bytes for an object. */
  def get(objectName: String): IO[NatsError, Chunk[Byte]]

  /** Retrieve metadata for an object (without downloading data). */
  def getInfo(objectName: String): IO[NatsError, ObjectSummary]

  /** Soft-delete an object (marks as deleted; history preserved). */
  def delete(objectName: String): IO[NatsError, ObjectSummary]

  /** Update the metadata of an existing object. */
  def updateMeta(objectName: String, meta: ObjectMeta): IO[NatsError, ObjectSummary]

  /** List all non-deleted objects in the bucket. */
  def list: IO[NatsError, List[ObjectSummary]]

  /** Bucket status and configuration. */
  def getStatus: IO[NatsError, ObjectStoreBucketStatus]

  /** Stream changes to objects in this bucket. Never completes unless interrupted. */
  def watch: ZStream[Any, NatsError, ObjectSummary]
}

/** Service for managing Object Store buckets. */
trait ObjectStoreManagement {
  def create(config: ObjectStoreConfig): IO[NatsError, ObjectStoreBucketStatus]
  def delete(bucketName: String): IO[NatsError, Unit]
  def getBucketNames: IO[NatsError, List[String]]
  def getStatus(bucketName: String): IO[NatsError, ObjectStoreBucketStatus]
}

object ObjectStore {

  /** Create an ObjectStore service bound to a specific bucket.
    *
    * The bucket must already exist. Use ObjectStoreManagement.create to create it.
    */
  def bucket(bucketName: String): ZIO[Nats, NatsError, ObjectStore] =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.attempt(nats.underlying.objectStore(bucketName)).mapBoth(NatsError.fromThrowable, new ObjectStoreLive(_))
    }
}

object ObjectStoreManagement {

  def create(config: ObjectStoreConfig): ZIO[ObjectStoreManagement, NatsError, ObjectStoreBucketStatus] =
    ZIO.serviceWithZIO[ObjectStoreManagement](_.create(config))


  def delete(bucketName: String): ZIO[ObjectStoreManagement, NatsError, Unit] =
    ZIO.serviceWithZIO[ObjectStoreManagement](_.delete(bucketName))

  val live: ZLayer[Nats, NatsError, ObjectStoreManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        osm  <- ZIO.attempt(nats.underlying.objectStoreManagement())
                  .mapError(NatsError.fromThrowable)
      } yield new ObjectStoreManagementLive(osm)
    }
}

private[nats] final class ObjectStoreLive(os: JObjectStore) extends ObjectStore {

  override def bucketName: String = os.getBucketName

  override def put(objectName: String, data: Chunk[Byte]): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.put(objectName, data.toArray)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def put(meta: ObjectMeta, data: Chunk[Byte]): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.put(meta, new ByteArrayInputStream(data.toArray))).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def get(objectName: String): IO[NatsError, Chunk[Byte]] =
    ZIO.attemptBlocking {
      val baos = new ByteArrayOutputStream()
      os.get(objectName, baos)
      Chunk.fromArray(baos.toByteArray)
    }.mapError(NatsError.fromThrowable)

  override def getInfo(objectName: String): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.getInfo(objectName)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def delete(objectName: String): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.delete(objectName)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def updateMeta(objectName: String, meta: ObjectMeta): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.updateMeta(objectName, meta)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def list: IO[NatsError, List[ObjectSummary]] =
    ZIO.attemptBlocking(os.getList.asScala.toList).mapBoth(NatsError.fromThrowable, _.map(ObjectSummary.fromJava))

  override def getStatus: IO[NatsError, ObjectStoreBucketStatus] =
    ZIO.attemptBlocking(os.getStatus).mapBoth(NatsError.fromThrowable, ObjectStoreBucketStatus.fromJava)

  override def watch: ZStream[Any, NatsError, ObjectSummary] =
    ZStream.unwrapScoped {
      for {
        queue <- ZIO.acquireRelease(Queue.unbounded[ObjectSummary])(_.shutdown)
        watcher = new JObjectStoreWatcher {
          override def watch(info: JObjectInfo): Unit =
            zio.Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe.run(queue.offer(ObjectSummary.fromJava(info)))
                .getOrThrowFiberFailure()
            }
          override def endOfData(): Unit = ()
        }
        _ <- ZIO.acquireRelease(
               ZIO.attemptBlocking(os.watch(watcher)).mapError(NatsError.fromThrowable)
             )(sub => ZIO.attemptBlocking(sub.unsubscribe()).ignoreLogged)
      } yield ZStream.fromQueue(queue)
    }
}

private[nats] final class ObjectStoreManagementLive(osm: JObjectStoreManagement) extends ObjectStoreManagement {

  override def create(config: ObjectStoreConfig): IO[NatsError, ObjectStoreBucketStatus] =
    ZIO.attemptBlocking(osm.create(config.toJava)).mapError(NatsError.fromThrowable)
      .map(ObjectStoreBucketStatus.fromJava)

  override def delete(bucketName: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(osm.delete(bucketName)).mapError(NatsError.fromThrowable)

  override def getBucketNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(osm.getBucketNames().asScala.toList).mapError(NatsError.fromThrowable)

  override def getStatus(bucketName: String): IO[NatsError, ObjectStoreBucketStatus] =
    ZIO.attemptBlocking(osm.getStatus(bucketName)).mapError(NatsError.fromThrowable)
      .map(ObjectStoreBucketStatus.fromJava)
}
