package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object KeyValueSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Key-Value Store")(

    test("create bucket, put, get, and delete key") {
      for {
        kvm   <- ZIO.service[KeyValueManagement]
        _     <- kvm.create(KeyValueConfig(name = "kv-basic", storageType = StorageType.Memory))
        kv    <- KeyValue.bucket("kv-basic")
        rev   <- kv.put("k1", "hello")
        entry <- kv.get("k1")
        _     <- kv.delete("k1")
        after <- kv.get("k1")
        _     <- kvm.delete("kv-basic")
      } yield assertTrue(
        rev == 1L,
        entry.isDefined,
        entry.get.value.toArray.sameElements("hello".getBytes),
        // After delete, entry exists with DELETE operation marker
        after.forall(_.operation == KeyValueOperation.DELETE)
      )
    },

    test("create (put-if-absent) and CAS update") {
      for {
        kvm  <- ZIO.service[KeyValueManagement]
        _    <- kvm.create(KeyValueConfig(name = "kv-cas", storageType = StorageType.Memory))
        kv   <- KeyValue.bucket("kv-cas")
        rev1 <- kv.create("cas-key", Chunk.fromArray("v1".getBytes))
        rev2 <- kv.update("cas-key", Chunk.fromArray("v2".getBytes), rev1)
        // Wrong revision must fail
        fail <- kv.update("cas-key", Chunk.fromArray("v3".getBytes), rev1).either
        _    <- kvm.delete("kv-cas")
      } yield assertTrue(
        rev1 == 1L,
        rev2 == 2L,
        fail.isLeft
      )
    },

    test("watch a key emits changes") {
      for {
        kvm      <- ZIO.service[KeyValueManagement]
        _        <- kvm.create(KeyValueConfig(name = "kv-watch", storageType = StorageType.Memory))
        kv       <- KeyValue.bucket("kv-watch")
        received <- Promise.make[Nothing, KeyValueEntry]
        fiber    <- kv.watch("watch-key")
                      .filter(_.operation == KeyValueOperation.PUT)
                      .tap(e => received.succeed(e))
                      .take(1)
                      .runDrain
                      .fork
        _        <- ZIO.sleep(300.millis)
        _        <- kv.put("watch-key", "watched")
        entry    <- received.await
        _        <- fiber.interrupt
        _        <- kvm.delete("kv-watch")
      } yield assertTrue(entry.value.toArray.sameElements("watched".getBytes))
    },

    test("list keys in bucket") {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(KeyValueConfig(name = "kv-keys", storageType = StorageType.Memory))
        kv  <- KeyValue.bucket("kv-keys")
        _   <- kv.put("a", "1")
        _   <- kv.put("b", "2")
        _   <- kv.put("c", "3")
        ks  <- kv.keys
        _   <- kvm.delete("kv-keys")
      } yield assertTrue(ks.sorted == List("a", "b", "c"))
    },

    test("history tracks revisions") {
      for {
        kvm  <- ZIO.service[KeyValueManagement]
        _    <- kvm.create(
                  KeyValueConfig(name = "kv-hist", storageType = StorageType.Memory, maxHistoryPerKey = 10)
                )
        kv   <- KeyValue.bucket("kv-hist")
        _    <- kv.put("h", "v1")
        _    <- kv.put("h", "v2")
        _    <- kv.put("h", "v3")
        hist <- kv.history("h")
        _    <- kvm.delete("kv-hist")
      } yield assertTrue(hist.size == 3)
    }

  ).provideShared(
    NatsTestLayers.nats,
    KeyValueManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
