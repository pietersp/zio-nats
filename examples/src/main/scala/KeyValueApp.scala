import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

case class FeatureFlag(enabled: Boolean, rolloutPercent: Int, description: String)
object FeatureFlag {
  given schema: Schema[FeatureFlag] = Schema.derived
}

/**
 * Key-Value example: typed writes, CAS, revision history, and live watching.
 *
 * NATS KV is built on JetStream and stores typed values under string keys
 * with revisioned history and change-watch streams. This example covers:
 *
 *   1. put / get      — basic typed read and write
 *   2. create / update — conditional writes: create-if-absent and
 *                        compare-and-swap by revision
 *   3. watch           — live stream of KvEvents; emits Put, Delete, and
 *                        Purge events as keys change
 *   4. history         — full revision history for a key
 *   5. keys            — enumerate all keys in the bucket
 *
 * Requires a JetStream-enabled NATS server:
 *   docker run -p 4222:4222 nats -js
 *
 * Run with: sbt "zioNatsExamples/runMain KeyValueApp"
 */
object KeyValueApp extends ZIOAppDefault {

  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  private val bucket = "feature-flags"

  val program: ZIO[Nats & KeyValueManagement, NatsError, Unit] =
    for {
      kvm <- ZIO.service[KeyValueManagement]

      // Create the bucket with history enabled (keeps up to 10 revisions per key)
      _ <- kvm.create(
             KeyValueConfig(
               name             = bucket,
               storageType      = StorageType.Memory,
               maxHistoryPerKey = 10
             )
           )
      kv <- KeyValue.bucket(bucket)

      // -----------------------------------------------------------------------
      // 1. put / get — basic typed writes and reads
      // -----------------------------------------------------------------------

      _    <- Console.printLine("[put/get]").orDie
      rev1 <- kv.put("dark-mode",   FeatureFlag(false,   0, "Dark mode UI"))
      rev2 <- kv.put("new-checkout", FeatureFlag(false,  0, "Redesigned checkout flow"))
      rev3 <- kv.put("search-v2",   FeatureFlag(false,   0, "Upgraded search engine"))
      _    <- Console.printLine(s"  wrote 3 flags at revs $rev1, $rev2, $rev3").orDie

      entry <- kv.get[FeatureFlag]("dark-mode")
      _ <- Console.printLine(
             s"  get dark-mode → enabled=${entry.map(_.value.enabled)}, rev=${entry.map(_.revision)}"
           ).orDie

      // -----------------------------------------------------------------------
      // 2. create / update — conditional (CAS) writes
      //
      // create: succeeds only if the key does not yet exist; fails with
      //         JetStreamApiError if it already has a value.
      // update: succeeds only if the current revision matches expectedRevision;
      //         fails with JetStreamApiError on mismatch (optimistic locking).
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[create/update]").orDie

      rev4 <- kv.create("beta-dashboard", FeatureFlag(true, 100, "Internal beta dashboard"))
      _    <- Console.printLine(s"  created beta-dashboard at rev $rev4").orDie

      rev5 <- kv.update("dark-mode", FeatureFlag(true, 10, "Dark mode UI"), expectedRevision = rev1)
      _    <- Console.printLine(s"  updated dark-mode to 10% rollout at rev $rev5").orDie

      rev6 <- kv.update("dark-mode", FeatureFlag(true, 50, "Dark mode UI"), expectedRevision = rev5)
      _    <- Console.printLine(s"  updated dark-mode to 50% rollout at rev $rev6").orDie

      // -----------------------------------------------------------------------
      // 3. watch — live stream of KvEvents
      //
      // watchAll emits a KvEvent for every bucket change while the stream is
      // alive. updatesOnly skips the initial values for existing keys.
      // We take 5 events (3 puts + 1 delete + 1 purge) then interrupt.
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[watch] starting watcher (updatesOnly)…").orDie

      watchFiber <- kv
                      .watchAll[FeatureFlag](KeyValueWatchOptions(updatesOnly = true))
                      .take(5)
                      .tap { event =>
                        val msg = event match {
                          case KvEvent.Put(env)  =>
                            s"  Put   ${env.key} rev=${env.revision} " +
                            s"enabled=${env.value.enabled} rollout=${env.value.rolloutPercent}%"
                          case KvEvent.Delete(e) => s"  Del   ${e.key} rev=${e.revision}"
                          case KvEvent.Purge(e)  => s"  Purge ${e.key} rev=${e.revision}"
                        }
                        Console.printLine(msg).orDie
                      }
                      .runDrain
                      .fork

      // Give the watcher subscription a moment to register before writing
      _ <- ZIO.sleep(200.millis)

      _ <- kv.put("dark-mode",    FeatureFlag(true, 100, "Dark mode UI"))
      _ <- kv.put("new-checkout", FeatureFlag(true,  5,  "Redesigned checkout flow"))
      _ <- kv.put("search-v2",    FeatureFlag(true,  20, "Upgraded search engine"))
      _ <- kv.delete("beta-dashboard")
      _ <- kv.purge("new-checkout")

      _ <- watchFiber.join

      // -----------------------------------------------------------------------
      // 4. history — all revisions for a key, oldest to newest
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[history] dark-mode:").orDie
      revisions <- kv.history[FeatureFlag]("dark-mode")
      _ <- ZIO.foreach(revisions) { env =>
             Console.printLine(
               s"  rev=${env.revision} enabled=${env.value.enabled} rollout=${env.value.rolloutPercent}%"
             ).orDie
           }

      // -----------------------------------------------------------------------
      // 5. keys — enumerate all live keys in the bucket
      // -----------------------------------------------------------------------

      _ <- Console.printLine("\n[keys]").orDie
      allKeys <- kv.keys()
      _ <- Console.printLine(s"  ${allKeys.sorted.mkString(", ")}").orDie

      // Cleanup
      _ <- kvm.delete(bucket)
      _ <- Console.printLine("\nDone.").orDie
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(
        ZLayer.succeed(NatsConfig.default) >>> Nats.live >+>
          KeyValueManagement.live
      )
      .mapError(e => new RuntimeException(e.getMessage))
}
