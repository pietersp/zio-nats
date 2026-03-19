import zio.*
import zio.nats.*
import zio.stream.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

case class AppConfig(version: Int, logLevel: String, maxConnections: Int)
object AppConfig {
  given schema: Schema[AppConfig] = Schema.derived
}

/**
 * Object Store example: typed blobs, streaming I/O, metadata, links, and watching.
 *
 * NATS ObjectStore is built on JetStream and is designed for named binary
 * objects of arbitrary size. Objects are chunked automatically on the server.
 * This example covers:
 *
 *   1. put / get         — typed write and read (JSON-encoded config)
 *   2. put with ObjectMeta — custom description and headers alongside the data
 *   3. putStream / getStream — streaming I/O for large objects without buffering
 *                             the full content in memory
 *   4. updateMeta        — rename or re-describe an existing object
 *   5. addLink           — create an alias that points to another object
 *   6. watch             — live stream of ObjectSummary as objects change
 *   7. list              — enumerate all non-deleted objects in the bucket
 *   8. delete / getInfo  — soft-delete and inspect deleted metadata
 *
 * Requires a JetStream-enabled NATS server:
 *   docker run -p 4222:4222 nats -js
 *
 * Run with: sbt "zioNatsExamples/runMain ObjectStoreApp"
 */
object ObjectStoreApp extends ZIOAppDefault {

  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  private val bucket = "assets"

  val program: ZIO[Nats & ObjectStoreManagement, NatsError, Unit] =
    for {
      osm <- ZIO.service[ObjectStoreManagement]

      _ <- osm.create(ObjectStoreConfig(name = bucket, storageType = StorageType.Memory))
      os <- ObjectStore.bucket(bucket)

      // -----------------------------------------------------------------------
      // 1. put / get — typed write and read
      //
      // NatsCodec[AppConfig] encodes to JSON; get decodes it back.
      // ObjectData bundles the decoded value with server-side metadata.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("[put/get] typed config:").orDie

      summary <- os.put("config.json", AppConfig(version = 3, logLevel = "INFO", maxConnections = 100))
      _ <- Console.printLine(
             s"  stored config.json: ${summary.size} bytes in ${summary.chunks} chunk(s)"
           ).orDie

      obj <- os.get[AppConfig]("config.json")
      _ <- Console.printLine(
             s"  retrieved: version=${obj.value.version} logLevel=${obj.value.logLevel}"
           ).orDie

      // -----------------------------------------------------------------------
      // 2. put with ObjectMeta — description and custom headers
      //
      // ObjectMeta lets you attach a description and NATS headers to any object,
      // useful for content-type tagging, ownership, or audit metadata.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[put with ObjectMeta] raw bytes + metadata:").orDie

      pngBytes = Chunk.fill(512)(0xff.toByte) // simulate a small PNG
      meta = ObjectMeta(
               name        = "logo.png",
               description = Some("Company logo"),
               headers     = Headers("Content-Type" -> "image/png", "X-Owner" -> "design-team")
             )
      s2 <- os.put(meta, pngBytes)
      _ <- Console.printLine(s"  stored logo.png: ${s2.size} bytes, desc='${s2.description.getOrElse("")}'").orDie

      // -----------------------------------------------------------------------
      // 3. putStream / getStream — streaming large objects
      //
      // putStream consumes a ZStream[Any, Nothing, Byte] without ever holding
      // the full content in memory — ideal for large files.
      // getStream returns a lazy byte stream delivered chunk by chunk.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[putStream/getStream] 1 MB synthetic blob:").orDie

      largeData    = ZStream.fromChunk(Chunk.fill(1024 * 1024)(0xab.toByte))
      s3          <- os.putStream("large-binary.bin", largeData)
      _ <- Console.printLine(s"  uploaded: ${s3.size} bytes in ${s3.chunks} chunks").orDie

      downloadedSize <- os.getStream("large-binary.bin").runCount
      _ <- Console.printLine(s"  streamed back: $downloadedSize bytes").orDie

      // -----------------------------------------------------------------------
      // 4. updateMeta — rename or re-describe an existing object
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[updateMeta]").orDie

      _ <- os.updateMeta("config.json", ObjectMeta(
                           name        = "config.json",
                           description = Some("Application config v3")
                         ))
      info <- os.getInfo("config.json")
      _ <- Console.printLine(s"  updated description: '${info.description.getOrElse("")}'").orDie

      // -----------------------------------------------------------------------
      // 5. addLink — create an alias pointing to another object
      //
      // A link is a lightweight indirection: reads on the link name are served
      // from the target object. Useful for stable "latest" pointers.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[addLink]").orDie

      _ <- os.addLink("config-latest.json", "config.json")
      linked <- os.get[AppConfig]("config-latest.json")
      _ <- Console.printLine(s"  config-latest.json → version=${linked.value.version}").orDie

      // -----------------------------------------------------------------------
      // 6. watch — live stream of ObjectSummary as objects change
      //
      // watch emits an ObjectSummary for every put, update, and delete while
      // the stream is alive. updatesOnly skips the current state snapshot.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[watch] starting watcher (updatesOnly)…").orDie

      watchFiber <- os
                      .watch(ObjectStoreWatchOptions(updatesOnly = true))
                      .take(3)
                      .tap { s =>
                        Console.printLine(
                          if (s.isDeleted) s"  deleted: ${s.name}"
                          else             s"  put: ${s.name} (${s.size} bytes)"
                        ).orDie
                      }
                      .runDrain
                      .fork

      _ <- ZIO.sleep(200.millis)

      _ <- os.put("patch-notes.txt", "v3.1 — performance improvements")
      _ <- os.put("readme.txt",      "See config.json for settings.")
      _ <- os.delete("large-binary.bin")

      _ <- watchFiber.join

      // -----------------------------------------------------------------------
      // 7. list — enumerate all non-deleted objects
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[list]").orDie

      objects <- os.list
      _ <- ZIO.foreach(objects.sortBy(_.name)) { s =>
             Console.printLine(s"  ${s.name} (${s.size} bytes)").orDie
           }

      // -----------------------------------------------------------------------
      // 8. delete / getInfo(includingDeleted) — soft delete
      //
      // delete places a deleted marker; the object's metadata is still
      // accessible via getInfo(includingDeleted = true).
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[delete/getInfo]").orDie

      _ <- os.delete("logo.png")
      tombstone <- os.getInfo("logo.png", includingDeleted = true)
      _ <- Console.printLine(s"  logo.png isDeleted=${tombstone.isDeleted}").orDie

      // Cleanup
      _ <- osm.delete(bucket)
      _ <- Console.printLine("\nDone.").orDie
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(
        ZLayer.succeed(NatsConfig.default) >>> Nats.live >+>
          ObjectStoreManagement.live
      )
      .mapError(e => new RuntimeException(e.getMessage))
}
