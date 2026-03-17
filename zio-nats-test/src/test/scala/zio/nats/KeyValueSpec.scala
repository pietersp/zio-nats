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
        after.forall(_.operation == KeyValueOperation.Delete)
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
                   .filter(_.operation == KeyValueOperation.Put)
                   .tap(e => received.succeed(e))
                   .take(1)
                   .runDrain
                   .fork
        _     <- ZIO.sleep(300.millis)
        _     <- kv.put("watch-key", "watched")
        entry <- received.await
        _     <- fiber.interrupt
        _     <- kvm.delete("kv-watch")
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
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(
               KeyValueConfig(name = "kv-hist", storageType = StorageType.Memory, maxHistoryPerKey = 10)
             )
        kv   <- KeyValue.bucket("kv-hist")
        _    <- kv.put("h", "v1")
        _    <- kv.put("h", "v2")
        _    <- kv.put("h", "v3")
        hist <- kv.history("h")
        _    <- kvm.delete("kv-hist")
      } yield assertTrue(hist.size == 3)
    },

    test("bucket-level TTL is reflected in status") {
      for {
        kvm    <- ZIO.service[KeyValueManagement]
        status <- kvm.create(
                    KeyValueConfig(name = "kv-ttl-bucket", storageType = StorageType.Memory, ttl = Some(10.seconds))
                  )
        _      <- kvm.delete("kv-ttl-bucket")
      } yield assertTrue(status.ttl.exists(_ == 10.seconds))
    },

    test("create with per-entry TTL stores the entry") {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(
                 KeyValueConfig(
                   name = "kv-entry-ttl",
                   storageType = StorageType.Memory,
                   ttl = Some(60.seconds),
                   limitMarkerTtl = Some(10.seconds)
                 )
               )
        kv  <- KeyValue.bucket("kv-entry-ttl")
        rev <- kv.create("ttl-key", "hello", 5.seconds)
        e   <- kv.get("ttl-key")
        _   <- kvm.delete("kv-entry-ttl")
      } yield assertTrue(rev == 1L, e.exists(_.valueAsString == "hello"))
    },

    test("watch with IGNORE_DELETE filters out delete markers") {
      for {
        kvm     <- ZIO.service[KeyValueManagement]
        _       <- kvm.create(KeyValueConfig(name = "kv-watch-del", storageType = StorageType.Memory))
        kv      <- KeyValue.bucket("kv-watch-del")
        _       <- kv.put("d", "v1")
        _       <- kv.delete("d")
        entries <- kv
                     .watch("d", KeyValueWatchOptions(ignoreDeletes = true, includeHistory = true))
                     .take(2)
                     .runCollect
                     .timeout(5.seconds)
                     .map(_.getOrElse(Chunk.empty))
        _       <- kvm.delete("kv-watch-del")
      } yield assertTrue(
        entries.forall(_.operation == KeyValueOperation.Put)
      )
    },

    test("watch fromRevision replays from a specific point") {
      for {
        kvm  <- ZIO.service[KeyValueManagement]
        _    <- kvm.create(
                  KeyValueConfig(name = "kv-watch-rev", storageType = StorageType.Memory, maxHistoryPerKey = 10)
                )
        kv   <- KeyValue.bucket("kv-watch-rev")
        rev1 <- kv.put("r", "v1")
        _    <- kv.put("r", "v2")
        _    <- kv.put("r", "v3")
        // replay only from rev2 onwards
        entries <- kv
                     .watch("r", KeyValueWatchOptions(fromRevision = Some(rev1 + 1)))
                     .take(2)
                     .runCollect
                     .timeout(5.seconds)
                     .map(_.getOrElse(Chunk.empty))
        _       <- kvm.delete("kv-watch-rev")
      } yield assertTrue(
        entries.size == 2,
        entries.map(_.valueAsString).toList == List("v2", "v3")
      )
    },

    test("watch multiple keys delivers events for each key") {
      for {
        kvm      <- ZIO.service[KeyValueManagement]
        _        <- kvm.create(KeyValueConfig(name = "kv-watch-multi", storageType = StorageType.Memory))
        kv       <- KeyValue.bucket("kv-watch-multi")
        received <- Ref.make(List.empty[String])
        fiber    <- kv
                      .watch(List("x", "y"), KeyValueWatchOptions(updatesOnly = true))
                      .tap(e => received.update(e.key :: _))
                      .take(2)
                      .runDrain
                      .fork
        _        <- ZIO.sleep(300.millis)
        _        <- kv.put("x", "1")
        _        <- kv.put("y", "2")
        _        <- fiber.join.timeout(5.seconds)
        keys     <- received.get
        _        <- kvm.delete("kv-watch-multi")
      } yield assertTrue(keys.toSet == Set("x", "y"))
    },

    test("purgeDeletes removes tombstone entries") {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(KeyValueConfig(name = "kv-purgdel", storageType = StorageType.Memory, maxHistoryPerKey = 5))
        kv  <- KeyValue.bucket("kv-purgdel")
        _   <- kv.put("pd", "v1")
        _   <- kv.delete("pd")
        _   <- kv.purgeDeletes(-1.millis) // negative = remove ALL markers regardless of age
        hist <- kv.history("pd")
        _    <- kvm.delete("kv-purgdel")
      } yield assertTrue(hist.forall(_.operation != KeyValueOperation.Delete))
    },

    test("keys with filter returns matching keys only") {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(KeyValueConfig(name = "kv-keyfilter", storageType = StorageType.Memory))
        kv  <- KeyValue.bucket("kv-keyfilter")
        _   <- kv.put("foo.1", "a")
        _   <- kv.put("foo.2", "b")
        _   <- kv.put("bar.1", "c")
        ks  <- kv.keys("foo.*")
        _   <- kvm.delete("kv-keyfilter")
      } yield assertTrue(ks.toSet == Set("foo.1", "foo.2"))
    },

    test("keys with multiple filters returns union of matches") {
      for {
        kvm <- ZIO.service[KeyValueManagement]
        _   <- kvm.create(KeyValueConfig(name = "kv-keyfilters", storageType = StorageType.Memory))
        kv  <- KeyValue.bucket("kv-keyfilters")
        _   <- kv.put("foo.1", "a")
        _   <- kv.put("bar.1", "b")
        _   <- kv.put("baz.1", "c")
        ks  <- kv.keys(List("foo.*", "bar.*"))
        _   <- kvm.delete("kv-keyfilters")
      } yield assertTrue(ks.toSet == Set("foo.1", "bar.1"))
    },

    test("KeyValueManagement.getStatuses returns status for all buckets") {
      for {
        kvm      <- ZIO.service[KeyValueManagement]
        _        <- kvm.create(KeyValueConfig(name = "kv-stat-a", storageType = StorageType.Memory))
        _        <- kvm.create(KeyValueConfig(name = "kv-stat-b", storageType = StorageType.Memory))
        statuses <- kvm.getStatuses
        names     = statuses.map(_.bucketName).toSet
        _        <- kvm.delete("kv-stat-a")
        _        <- kvm.delete("kv-stat-b")
      } yield assertTrue(names.contains("kv-stat-a"), names.contains("kv-stat-b"))
    },

    test("watchAll with UPDATES_ONLY skips existing values") {
      for {
        kvm      <- ZIO.service[KeyValueManagement]
        _        <- kvm.create(KeyValueConfig(name = "kv-watch-upd", storageType = StorageType.Memory))
        kv       <- KeyValue.bucket("kv-watch-upd")
        _        <- kv.put("existing", "old")
        received <- Promise.make[Nothing, KeyValueEntry]
        fiber    <- kv
                      .watchAll(KeyValueWatchOptions(updatesOnly = true))
                      .tap(e => received.succeed(e))
                      .take(1)
                      .runDrain
                      .fork
        _        <- ZIO.sleep(300.millis)
        _        <- kv.put("new-key", "fresh")
        entry    <- received.await
        _        <- fiber.interrupt
        _        <- kvm.delete("kv-watch-upd")
      } yield assertTrue(entry.key == "new-key")
    }
  ).provideShared(
    NatsTestLayers.nats,
    KeyValueManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
