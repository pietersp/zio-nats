package zio.nats.objectstore

import io.nats.client.api.{ObjectInfo as JObjectInfo, ObjectStoreWatcher as JObjectStoreWatcher}
import io.nats.client.{ObjectStore as JObjectStore, ObjectStoreManagement as JObjectStoreManagement}
import zio.*
import zio.nats.{Nats, NatsCodec, NatsError}
import zio.stream.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import scala.jdk.CollectionConverters.*

/**
 * Service for object store operations on a single NATS OS bucket.
 *
 * NATS ObjectStore is built on JetStream and is optimised for storing large,
 * named blobs. Objects are chunked automatically on the server side. Use
 * [[putStream]] / [[getStream]] for very large objects to avoid loading them
 * fully into memory.
 *
 * Obtain an instance via [[ObjectStore.bucket]] (requires a [[zio.nats.Nats]]
 * connection in scope). Use [[ObjectStoreManagement]] to create or delete
 * buckets.
 *
 * ==Example==
 * {{{
 * for {
 *   os      <- ObjectStore.bucket("assets")
 *   summary <- os.put("logo.png", pngBytes)
 *   _       <- Console.printLine(s"Stored ${summary.size} bytes in ${summary.chunks} chunks")
 * } yield ()
 * }}}
 */
trait ObjectStore {

  /** The name of the Object Store bucket this service is bound to. */
  def bucketName: String

  private[nats] def underlying: JObjectStore

  /**
   * Encode `data` and store under objectName.
   *
   * Pass `Chunk[Byte]` to store raw bytes.
   */
  def put[A: NatsCodec](objectName: String, data: A): IO[NatsError, ObjectSummary]

  /**
   * Encode `data` and store with custom metadata.
   *
   * Pass `Chunk[Byte]` to store raw bytes.
   */
  def put[A: NatsCodec](meta: ObjectMeta, data: A): IO[NatsError, ObjectSummary]

  /**
   * Retrieve and decode an object to type A.
   *
   * Returns an [[ObjectData]] containing both the decoded value and the
   * [[ObjectSummary]] metadata (name, size, chunks, description, etc.).
   *
   * Use `get[Chunk[Byte]](name)` to retrieve raw bytes. For very large objects
   * prefer [[getStream]] to avoid loading the full object into memory.
   */
  def get[A: NatsCodec](objectName: String): IO[NatsError, ObjectData[A]]

  /**
   * Store raw bytes from a ZStream without buffering the full content in
   * memory. Suitable for large objects. The stream is consumed exactly once.
   */
  def putStream(objectName: String, data: ZStream[Any, Nothing, Byte]): IO[NatsError, ObjectSummary]

  /**
   * Store raw bytes from a ZStream with custom metadata.
   */
  def putStream(meta: ObjectMeta, data: ZStream[Any, Nothing, Byte]): IO[NatsError, ObjectSummary]

  /**
   * Retrieve an object as a lazy byte stream. Bytes are delivered as they
   * arrive from the server — the full object is never held in memory at once.
   */
  def getStream(objectName: String): ZStream[Any, NatsError, Byte]

  /**
   * Retrieve metadata for an object (without downloading data). Pass
   * `includingDeleted = true` to inspect a deleted object's metadata.
   */
  def getInfo(objectName: String, includingDeleted: Boolean = false): IO[NatsError, ObjectSummary]

  /** Soft-delete an object (marks as deleted; history preserved). */
  def delete(objectName: String): IO[NatsError, ObjectSummary]

  /** Update the metadata of an existing object. */
  def updateMeta(objectName: String, meta: ObjectMeta): IO[NatsError, ObjectSummary]

  /**
   * Create a link (alias) from `linkName` to the object named
   * `targetObjectName` in this bucket. A link cannot point to another link.
   */
  def addLink(linkName: String, targetObjectName: String): IO[NatsError, ObjectSummary]

  /**
   * Create a bucket-level link named `linkName` that points to another
   * ObjectStore bucket. Accessing the link retrieves from that bucket.
   */
  def addBucketLink(linkName: String, targetStore: ObjectStore): IO[NatsError, ObjectSummary]

  /**
   * Seal (make read-only) this bucket. No further puts will be accepted.
   * Returns the updated bucket status.
   */
  def seal(): IO[NatsError, ObjectStoreBucketStatus]

  /** List all non-deleted objects in the bucket. */
  def list: IO[NatsError, List[ObjectSummary]]

  /** Bucket status and configuration. */
  def getStatus: IO[NatsError, ObjectStoreBucketStatus]

  /**
   * Stream changes to objects in this bucket. Never completes unless
   * interrupted. Pass `options` to filter or set the start position.
   */
  def watch(options: ObjectStoreWatchOptions = ObjectStoreWatchOptions.default): ZStream[Any, NatsError, ObjectSummary]
}

/**
 * Service for managing Object Store buckets.
 *
 * Provides administrative operations to create and delete OS buckets. Obtain an
 * instance via [[ObjectStoreManagement.live]] (requires [[zio.nats.Nats]] in
 * scope).
 */
trait ObjectStoreManagement {

  /** Create a new Object Store bucket with the given configuration. */
  def create(config: ObjectStoreConfig): IO[NatsError, ObjectStoreBucketStatus]

  /** Delete an Object Store bucket and all its objects. */
  def delete(bucketName: String): IO[NatsError, Unit]

  /** List the names of all Object Store buckets on this server. */
  def getBucketNames: IO[NatsError, List[String]]

  /** Get the current status and configuration for a named bucket. */
  def getStatus(bucketName: String): IO[NatsError, ObjectStoreBucketStatus]

  /** Get status information for all Object Store buckets. */
  def getStatuses: IO[NatsError, List[ObjectStoreBucketStatus]]
}

object ObjectStore {

  /**
   * Create an ObjectStore service bound to a specific bucket.
   *
   * The bucket must already exist. Use ObjectStoreManagement.create to create
   * it.
   */
  def bucket(bucketName: String): ZIO[Nats, NatsError, ObjectStore] =
    ZIO.serviceWithZIO[Nats] { nats =>
      ZIO.attempt(nats.underlying.objectStore(bucketName)).mapBoth(NatsError.fromThrowable, new ObjectStoreLive(_))
    }
}

object ObjectStoreManagement {

  val live: ZLayer[Nats, NatsError, ObjectStoreManagement] =
    ZLayer {
      for {
        nats <- ZIO.service[Nats]
        osm  <- ZIO
                 .attempt(nats.underlying.objectStoreManagement())
                 .mapError(NatsError.fromThrowable)
      } yield new ObjectStoreManagementLive(osm)
    }
}

private[nats] final class ObjectStoreLive(os: JObjectStore) extends ObjectStore {

  override def bucketName: String = os.getBucketName

  override private[nats] def underlying: JObjectStore = os

  private def encode[A: NatsCodec](objectName: String, data: A): IO[NatsError, Array[Byte]] =
    ZIO
      .attempt(NatsCodec[A].encode(data).toArray)
      .mapError(e => NatsError.SerializationError(s"Failed to encode object '$objectName': ${e.toString}", e))

  override def put[A: NatsCodec](objectName: String, data: A): IO[NatsError, ObjectSummary] =
    encode(objectName, data).flatMap(bytes =>
      ZIO.attemptBlocking(os.put(objectName, bytes)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)
    )

  override def put[A: NatsCodec](meta: ObjectMeta, data: A): IO[NatsError, ObjectSummary] =
    encode(meta.name, data).flatMap(bytes =>
      ZIO
        .attemptBlocking(os.put(meta.toJava, new ByteArrayInputStream(bytes)))
        .mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)
    )

  override def get[A: NatsCodec](objectName: String): IO[NatsError, ObjectData[A]] =
    ZIO.attemptBlocking {
      val baos = new ByteArrayOutputStream()
      val info = os.get(objectName, baos)
      (Chunk.fromArray(baos.toByteArray), ObjectSummary.fromJava(info))
    }.mapError(NatsError.fromThrowable).flatMap { case (bytes, summary) =>
      NatsCodec[A].decode(bytes) match {
        case Right(value) => ZIO.succeed(ObjectData(value, summary))
        case Left(err)    => ZIO.fail(NatsError.DecodingError(err.message, err))
      }
    }

  private val PipeBufferSize = 65536

  override def putStream(objectName: String, data: ZStream[Any, Nothing, Byte]): IO[NatsError, ObjectSummary] =
    putStreamInternal(Left(objectName), data)

  override def putStream(meta: ObjectMeta, data: ZStream[Any, Nothing, Byte]): IO[NatsError, ObjectSummary] =
    putStreamInternal(Right(meta), data)

  private def putStreamInternal(
    target: Either[String, ObjectMeta],
    data: ZStream[Any, Nothing, Byte]
  ): IO[NatsError, ObjectSummary] =
    ZIO.scoped {
      for {
        in  <- ZIO.succeed(new PipedInputStream(PipeBufferSize))
        out <- ZIO.succeed(new PipedOutputStream(in))
        // Pump ZStream data into the pipe concurrently; close pipe when done
        _ <- data.chunks
               .runForeach(chunk => ZIO.attemptBlocking(out.write(chunk.toArray)))
               .ensuring(ZIO.attempt(out.close()).ignoreLogged)
               .forkScoped
        // jnats reads from the pipe on this (blocking) thread
        result <- ZIO
                    .attemptBlocking(target match {
                      case Left(name) => os.put(name, in)
                      case Right(m)   => os.put(m.toJava, in)
                    })
                    .mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)
      } yield result
    }

  override def getStream(objectName: String): ZStream[Any, NatsError, Byte] =
    ZStream.unwrapScoped {
      for {
        out <- ZIO.succeed(new PipedOutputStream())
        in  <- ZIO.succeed(new PipedInputStream(out, PipeBufferSize))
        // jnats writes to the pipe on a background fiber; close pipe when done
        dl <- ZIO
                .attemptBlocking(os.get(objectName, out))
                .ensuring(ZIO.attempt(out.close()).ignoreLogged)
                .mapError(NatsError.fromThrowable)
                .forkScoped
      } yield ZStream
        .fromInputStream(in, PipeBufferSize)
        .mapError(NatsError.fromThrowable)
        // append a zero-element effect that propagates any download error
        .concat(ZStream.fromZIO(dl.join.unit).drain)
    }

  override def getInfo(objectName: String, includingDeleted: Boolean = false): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(Option(os.getInfo(objectName, includingDeleted))).mapError(NatsError.fromThrowable).flatMap {
      case Some(info) => ZIO.succeed(ObjectSummary.fromJava(info))
      case None       =>
        ZIO.fail(
          NatsError.ObjectStoreOperationFailed(s"Object not found: $objectName", new NoSuchElementException(objectName))
        )
    }

  override def delete(objectName: String): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.delete(objectName)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def updateMeta(objectName: String, meta: ObjectMeta): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking(os.updateMeta(objectName, meta.toJava)).mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def addLink(linkName: String, targetObjectName: String): IO[NatsError, ObjectSummary] =
    ZIO.attemptBlocking {
      val targetInfo = os.getInfo(targetObjectName)
      os.addLink(linkName, targetInfo)
    }.mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def addBucketLink(linkName: String, targetStore: ObjectStore): IO[NatsError, ObjectSummary] =
    ZIO
      .attemptBlocking(os.addBucketLink(linkName, targetStore.underlying))
      .mapBoth(NatsError.fromThrowable, ObjectSummary.fromJava)

  override def seal(): IO[NatsError, ObjectStoreBucketStatus] =
    ZIO.attemptBlocking(os.seal()).mapBoth(NatsError.fromThrowable, ObjectStoreBucketStatus.fromJava)

  override def list: IO[NatsError, List[ObjectSummary]] =
    ZIO.attemptBlocking(os.getList.asScala.toList).mapBoth(NatsError.fromThrowable, _.map(ObjectSummary.fromJava))

  override def getStatus: IO[NatsError, ObjectStoreBucketStatus] =
    ZIO.attemptBlocking(os.getStatus).mapBoth(NatsError.fromThrowable, ObjectStoreBucketStatus.fromJava)

  override def watch(
    options: ObjectStoreWatchOptions = ObjectStoreWatchOptions.default
  ): ZStream[Any, NatsError, ObjectSummary] =
    watchInternal(options)

  private def watchInternal(options: ObjectStoreWatchOptions): ZStream[Any, NatsError, ObjectSummary] =
    ZStream.asyncScoped[Any, NatsError, ObjectSummary] { emit =>
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val watcher = new JObjectStoreWatcher {
            override def watch(info: JObjectInfo): Unit =
              emit(ZIO.succeed(Chunk.single(ObjectSummary.fromJava(info))))
            override def endOfData(): Unit = ()
          }
          val jOpts = ObjectStoreWatchOptions.toJava(options)
          os.watch(watcher, jOpts*)
        }.mapError(NatsError.fromThrowable)
      )(sub => ZIO.attemptBlocking(sub.unsubscribe()).ignoreLogged)
    }
}

private[nats] final class ObjectStoreManagementLive(osm: JObjectStoreManagement) extends ObjectStoreManagement {

  override def create(config: ObjectStoreConfig): IO[NatsError, ObjectStoreBucketStatus] =
    ZIO
      .attemptBlocking(osm.create(config.toJava))
      .mapBoth(NatsError.fromThrowable, ObjectStoreBucketStatus.fromJava)

  override def delete(bucketName: String): IO[NatsError, Unit] =
    ZIO.attemptBlocking(osm.delete(bucketName)).mapError(NatsError.fromThrowable)

  override def getBucketNames: IO[NatsError, List[String]] =
    ZIO.attemptBlocking(osm.getBucketNames.asScala.toList).mapError(NatsError.fromThrowable)

  override def getStatus(bucketName: String): IO[NatsError, ObjectStoreBucketStatus] =
    ZIO
      .attemptBlocking(osm.getStatus(bucketName))
      .mapBoth(NatsError.fromThrowable, ObjectStoreBucketStatus.fromJava)

  override def getStatuses: IO[NatsError, List[ObjectStoreBucketStatus]] =
    ZIO
      .attemptBlocking(osm.getStatuses().asScala.toList)
      .mapBoth(NatsError.fromThrowable, _.map(ObjectStoreBucketStatus.fromJava))
}
