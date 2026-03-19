package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.stream.ZStream
import zio.test.*
import zio.test.TestAspect.*

object ObjectStoreSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Object Store")(
    test("create bucket, put, get, and delete an object") {
      for {
        osm  <- ZIO.service[ObjectStoreManagement]
        _    <- osm.create(ObjectStoreConfig(name = "os-basic", storageType = StorageType.Memory))
        os   <- ObjectStore.bucket("os-basic")
        info <- os.put("my-object", Chunk.fromArray("hello-object".getBytes))
        data <- os.get[Chunk[Byte]]("my-object")
        _    <- os.delete("my-object")
        _    <- osm.delete("os-basic")
      } yield assertTrue(
        info.name == "my-object",
        data.value.toArray.sameElements("hello-object".getBytes),
        data.summary.name == "my-object",
        data.summary.size == 12L
      )
    },

    test("list objects in bucket") {
      for {
        osm <- ZIO.service[ObjectStoreManagement]
        _   <- osm.create(ObjectStoreConfig(name = "os-list", storageType = StorageType.Memory))
        os  <- ObjectStore.bucket("os-list")
        _   <- os.put("obj-1", Chunk.fromArray("data1".getBytes))
        _   <- os.put("obj-2", Chunk.fromArray("data2".getBytes))
        lst <- os.list
        _   <- osm.delete("os-list")
      } yield assertTrue(lst.map(_.name).sorted == List("obj-1", "obj-2"))
    },

    test("large object is chunked and reassembled correctly") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-large", storageType = StorageType.Memory))
        os     <- ObjectStore.bucket("os-large")
        bigData = Chunk.fromArray(Array.fill(128 * 1024)(42.toByte))
        _      <- os.put("big-obj", bigData)
        got    <- os.get[Chunk[Byte]]("big-obj")
        _      <- osm.delete("os-large")
      } yield assertTrue(got.value == bigData)
    },

    test("ObjectStoreManagement.getStatuses returns status for all buckets") {
      for {
        osm      <- ZIO.service[ObjectStoreManagement]
        _        <- osm.create(ObjectStoreConfig(name = "os-stat-a", storageType = StorageType.Memory))
        _        <- osm.create(ObjectStoreConfig(name = "os-stat-b", storageType = StorageType.Memory))
        statuses <- osm.getStatuses
        names     = statuses.map(_.bucketName).toSet
        _        <- osm.delete("os-stat-a")
        _        <- osm.delete("os-stat-b")
      } yield assertTrue(names.contains("os-stat-a"), names.contains("os-stat-b"))
    },

    test("seal makes bucket read-only") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-seal", storageType = StorageType.Memory))
        os     <- ObjectStore.bucket("os-seal")
        _      <- os.put("pre-seal", Chunk.fromArray("data".getBytes))
        status <- os.seal()
        putErr <- os.put("post-seal", Chunk.fromArray("data".getBytes)).either
        _      <- osm.delete("os-seal")
      } yield assertTrue(
        status.isSealed,
        putErr.isLeft
      )
    },

    test("addLink creates an alias for an object") {
      for {
        osm  <- ZIO.service[ObjectStoreManagement]
        _    <- osm.create(ObjectStoreConfig(name = "os-link", storageType = StorageType.Memory))
        os   <- ObjectStore.bucket("os-link")
        _    <- os.put("original", Chunk.fromArray("link-data".getBytes))
        link <- os.addLink("my-link", "original")
        got  <- os.get[Chunk[Byte]]("my-link")
        _    <- osm.delete("os-link")
      } yield assertTrue(
        link.name == "my-link",
        got.value.toArray.sameElements("link-data".getBytes)
      )
    },

    test("getInfo fails for deleted objects; delete() returns deleted summary") {
      for {
        osm      <- ZIO.service[ObjectStoreManagement]
        _        <- osm.create(ObjectStoreConfig(name = "os-getinfo", storageType = StorageType.Memory))
        os       <- ObjectStore.bucket("os-getinfo")
        _        <- os.put("del-obj", Chunk.fromArray("hello".getBytes))
        deleted  <- os.delete("del-obj")
        notFound <- os.getInfo("del-obj").either
        _        <- osm.delete("os-getinfo")
      } yield assertTrue(
        deleted.name == "del-obj",
        deleted.isDeleted,
        notFound.isLeft
      )
    },

    test("watch with IGNORE_DELETE skips deleted entries") {
      for {
        osm     <- ZIO.service[ObjectStoreManagement]
        _       <- osm.create(ObjectStoreConfig(name = "os-watch-del", storageType = StorageType.Memory))
        os      <- ObjectStore.bucket("os-watch-del")
        _       <- os.put("obj-a", Chunk.fromArray("data".getBytes))
        _       <- os.delete("obj-a")
        entries <- os
                     .watch(ObjectStoreWatchOptions(ignoreDeletes = true, includeHistory = true))
                     .take(2)
                     .runCollect
                     .timeout(5.seconds)
                     .map(_.getOrElse(Chunk.empty))
        _ <- osm.delete("os-watch-del")
      } yield assertTrue(entries.forall(!_.isDeleted))
    },

    test("putStream and getStream round-trip a large object without full in-memory buffering") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-stream", storageType = StorageType.Memory))
        os     <- ObjectStore.bucket("os-stream")
        bigData = Chunk.fromArray(Array.fill(512 * 1024)(99.toByte))
        src     = ZStream.fromChunk(bigData)
        info   <- os.putStream("streamed-obj", src)
        got    <- os.getStream("streamed-obj").runCollect
        _      <- osm.delete("os-stream")
      } yield assertTrue(
        info.name == "streamed-obj",
        info.size == 512L * 1024,
        got == bigData
      )
    },

    test("watch emits object changes") {
      for {
        osm      <- ZIO.service[ObjectStoreManagement]
        _        <- osm.create(ObjectStoreConfig(name = "os-watch", storageType = StorageType.Memory))
        os       <- ObjectStore.bucket("os-watch")
        received <- Promise.make[Nothing, ObjectSummary]
        fiber    <- os.watch()
                   .filter(!_.isDeleted)
                   .tap(info => received.succeed(info))
                   .take(1)
                   .runDrain
                   .fork
        _    <- ZIO.sleep(300.millis)
        _    <- os.put("watched-obj", Chunk.fromArray("data".getBytes))
        info <- received.await
        _    <- fiber.interrupt
        _    <- osm.delete("os-watch")
      } yield assertTrue(info.name == "watched-obj")
    },

    test("put with ObjectMeta stores description and can be retrieved") {
      for {
        osm  <- ZIO.service[ObjectStoreManagement]
        _    <- osm.create(ObjectStoreConfig(name = "os-meta-put", storageType = StorageType.Memory))
        os   <- ObjectStore.bucket("os-meta-put")
        meta  = ObjectMeta(name = "meta-obj", description = Some("my-desc"), headers = Headers("X-Tag" -> "tag1"))
        info <- os.put(meta, Chunk.fromArray("meta-data".getBytes))
        got  <- os.getInfo("meta-obj")
        _    <- osm.delete("os-meta-put")
      } yield assertTrue(
        info.name == "meta-obj",
        got.description.contains("my-desc")
      )
    },

    test("putStream with ObjectMeta stores description and data correctly") {
      for {
        osm  <- ZIO.service[ObjectStoreManagement]
        _    <- osm.create(ObjectStoreConfig(name = "os-meta-stream", storageType = StorageType.Memory))
        os   <- ObjectStore.bucket("os-meta-stream")
        meta  = ObjectMeta(name = "stream-meta-obj", description = Some("streamed"))
        data  = ZStream.fromChunk(Chunk.fromArray("streamed-data".getBytes))
        info <- os.putStream(meta, data)
        got  <- os.getInfo("stream-meta-obj")
        _    <- osm.delete("os-meta-stream")
      } yield assertTrue(
        info.name == "stream-meta-obj",
        got.description.contains("streamed")
      )
    },

    test("updateMeta changes the description of an existing object") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-updmeta", storageType = StorageType.Memory))
        os     <- ObjectStore.bucket("os-updmeta")
        _      <- os.put("upd-obj", Chunk.fromArray("data".getBytes))
        newMeta = ObjectMeta(name = "upd-obj", description = Some("updated-desc"))
        _      <- os.updateMeta("upd-obj", newMeta)
        info   <- os.getInfo("upd-obj")
        _      <- osm.delete("os-updmeta")
      } yield assertTrue(info.description.contains("updated-desc"))
    },

    test("addBucketLink creates a cross-bucket link") {
      for {
        osm  <- ZIO.service[ObjectStoreManagement]
        _    <- osm.create(ObjectStoreConfig(name = "os-blink-src", storageType = StorageType.Memory))
        _    <- osm.create(ObjectStoreConfig(name = "os-blink-dst", storageType = StorageType.Memory))
        src  <- ObjectStore.bucket("os-blink-src")
        dst  <- ObjectStore.bucket("os-blink-dst")
        _    <- src.put("src-obj", Chunk.fromArray("data".getBytes))
        link <- dst.addBucketLink("src-link", src)
        _    <- osm.delete("os-blink-src")
        _    <- osm.delete("os-blink-dst")
      } yield assertTrue(link.name == "src-link")
    },

    test("getInfo with includingDeleted=true returns deleted object summary") {
      for {
        osm     <- ZIO.service[ObjectStoreManagement]
        _       <- osm.create(ObjectStoreConfig(name = "os-getinfo-del", storageType = StorageType.Memory))
        os      <- ObjectStore.bucket("os-getinfo-del")
        _       <- os.put("del-target", Chunk.fromArray("hello".getBytes))
        _       <- os.delete("del-target")
        deleted <- os.getInfo("del-target", includingDeleted = true)
        _       <- osm.delete("os-getinfo-del")
      } yield assertTrue(deleted.name == "del-target", deleted.isDeleted)
    },

    test("os.getStatus returns bucket status with correct name") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-status-inst", storageType = StorageType.Memory))
        os     <- ObjectStore.bucket("os-status-inst")
        status <- os.getStatus
        _      <- osm.delete("os-status-inst")
      } yield assertTrue(status.bucketName == "os-status-inst")
    },

    test("ObjectStoreManagement.getBucketNames returns list containing created bucket") {
      for {
        osm   <- ZIO.service[ObjectStoreManagement]
        _     <- osm.create(ObjectStoreConfig(name = "os-bucketnames", storageType = StorageType.Memory))
        names <- osm.getBucketNames
        _     <- osm.delete("os-bucketnames")
      } yield assertTrue(names.contains("os-bucketnames"))
    },

    test("ObjectStoreManagement.getStatus returns status for a named bucket") {
      for {
        osm    <- ZIO.service[ObjectStoreManagement]
        _      <- osm.create(ObjectStoreConfig(name = "os-mgmt-status", storageType = StorageType.Memory))
        status <- osm.getStatus("os-mgmt-status")
        _      <- osm.delete("os-mgmt-status")
      } yield assertTrue(status.bucketName == "os-mgmt-status")
    },

    test("watch with UPDATES_ONLY skips pre-existing objects") {
      for {
        osm      <- ZIO.service[ObjectStoreManagement]
        _        <- osm.create(ObjectStoreConfig(name = "os-watch-upd", storageType = StorageType.Memory))
        os       <- ObjectStore.bucket("os-watch-upd")
        _        <- os.put("existing", Chunk.fromArray("old".getBytes))
        received <- Promise.make[Nothing, ObjectSummary]
        fiber    <- os
                   .watch(ObjectStoreWatchOptions(updatesOnly = true))
                   .tap(info => received.succeed(info))
                   .take(1)
                   .runDrain
                   .fork
        _    <- ZIO.sleep(300.millis)
        _    <- os.put("new-obj", Chunk.fromArray("fresh".getBytes))
        info <- received.await
        _    <- fiber.interrupt
        _    <- osm.delete("os-watch-upd")
      } yield assertTrue(info.name == "new-obj")
    }
  ).provideShared(
    NatsTestLayers.nats,
    ObjectStoreManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
