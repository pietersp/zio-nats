package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object ObjectStoreSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Object Store")(
    test("create bucket, put, get, and delete an object") {
      for {
        osm     <- ZIO.service[ObjectStoreManagement]
        _       <- osm.create(ObjectStoreConfig(name = "os-basic", storageType = StorageType.Memory))
        os      <- ObjectStore.bucket("os-basic")
        info    <- os.put("my-object", Chunk.fromArray("hello-object".getBytes))
        data    <- os.get[Chunk[Byte]]("my-object")
        objInfo <- os.getInfo("my-object")
        _       <- os.delete("my-object")
        _       <- osm.delete("os-basic")
      } yield assertTrue(
        info.name == "my-object",
        data.toArray.sameElements("hello-object".getBytes),
        objInfo.name == "my-object",
        objInfo.size == 12L
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
      } yield assertTrue(got == bigData)
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

    test("watch emits object changes") {
      for {
        osm      <- ZIO.service[ObjectStoreManagement]
        _        <- osm.create(ObjectStoreConfig(name = "os-watch", storageType = StorageType.Memory))
        os       <- ObjectStore.bucket("os-watch")
        received <- Promise.make[Nothing, ObjectSummary]
        fiber    <- os.watch
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
    }
  ).provideShared(
    NatsTestLayers.nats,
    ObjectStoreManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
