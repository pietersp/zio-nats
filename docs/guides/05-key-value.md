---
id: key-value
title: Key-Value Store
---

# Key-Value Store

> A versioned key-value store with watch support, built on JetStream.

The **Key-Value store** is a named bucket of key-value pairs. Every write is a new revision —
the full history of each key is preserved (up to a configurable limit). You can read the
current value, read by revision, or watch a stream of changes in real time.

## Prerequisites

- [Quick start](../quickstart.md) completed
- JetStream-enabled NATS server:

```bash
docker run --rm -p 4222:4222 nats -js
```

## Setup

Create a bucket with `KeyValueManagement`:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.kv.*

val createBucket: ZIO[KeyValueManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[KeyValueManagement] { kvm =>
    kvm.create(KeyValueConfig(name = "config", storageType = StorageType.Memory)).unit
  }

val managementLayer =
  ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> KeyValueManagement.live
```

**What's happening:**

1. `KeyValueConfig(name = "config", ...)` — defines the bucket. `name` is the only required field.
2. `storageType = StorageType.Memory` — stores data in memory (fast, non-durable). Use `StorageType.File` for persistence across server restarts.
3. `kvm.create` creates the bucket if it does not exist. Use `kvm.update` to modify an existing bucket's config.

## Basic operations

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.kv.*

val kvOps: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")

    // Put — returns the new revision number
    rev <- kv.put("feature.flag", "true")
    _   <- ZIO.debug(s"Written at revision $rev")

    // Get — returns Some(KvEnvelope[String]) or None; type param selects the codec
    entry <- kv.get[String]("feature.flag")
    _     <- ZIO.foreach(entry)(e => ZIO.debug(s"Value: ${e.value}"))

    // Get by specific revision
    historical <- kv.get[String]("feature.flag", revision = Some(1L))

    // Compare-and-swap — create only if key does not exist
    _ <- kv.create("lock", Chunk.fromArray("locked".getBytes))

    // Create with per-entry TTL
    _ <- kv.create("lease", Chunk.fromArray("data".getBytes), ttl = Some(30.seconds))

    // Update only if the current revision matches
    _ <- kv.update("feature.flag", Chunk.fromArray("false".getBytes), expectedRevision = rev)

    // Soft delete — history is preserved, a delete marker is added
    _ <- kv.delete("stale-key")

    // Conditional delete
    _ <- kv.delete("feature.flag", expectedRevision = Some(2L))

    // Purge — removes all history for the key
    _ <- kv.purge("old-key")
  } yield ()
```

**What's happening:**

1. `KeyValue.bucket("config")` — obtains a `KeyValue` handle for the named bucket. This is a `ZIO[Nats, NatsError, KeyValue]` effect, so it needs `Nats` in scope.
2. `kv.put(key, value)` — encodes the value with `NatsCodec[A]` and writes it. `String` and `Chunk[Byte]` work out of the box. For domain types, put a `NatsCodec[A]` in scope (see [Serialization](./02-serialization.md)).
3. `kv.create` fails if the key already exists. Use it to implement distributed locks or idempotent initialisation.
4. `kv.update` fails if the current revision does not match — this is optimistic concurrency control.
5. `kv.delete` adds a tombstone marker but keeps the history. `kv.purge` removes all history, including the marker.

## Return types

`get` and `history` return decoded values wrapped in `KvEnvelope[A]`:

| `KvEnvelope[A]` field | Type | Description |
|---|---|---|
| `.value` | `A` | The decoded value |
| `.entry` | `KeyValueEntry` | Metadata — key, revision, operation, bucket name |
| `.entry.key` | `String` | The key |
| `.entry.revision` | `Long` | Revision number for this write |
| `.entry.bucketName` | `String` | Name of the bucket |

`watch` and `watchAll` emit `KvEvent[A]` — a sealed enum:

| `KvEvent[A]` variant | Contents | When |
|---|---|---|
| `KvEvent.Put(envelope)` | `KvEnvelope[A]` | A value was written |
| `KvEvent.Delete(entry)` | `KeyValueEntry` | Key was soft-deleted |
| `KvEvent.Purge(entry)` | `KeyValueEntry` | Key history was purged |

## Listing keys

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.kv.*

val listKeys: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")

    // Eager — loads all keys into a List
    allKeys     <- kv.keys()
    filtered    <- kv.keys(List("feature.*"))
    multiFilter <- kv.keys(List("feature.*", "flag.*"))

    // Streaming — memory-efficient for large buckets
    _ <- kv.consumeKeys().tap(k => ZIO.debug(k)).runDrain
    _ <- kv.consumeKeys(List("feature.*")).runDrain

    // Full history for a key — type param selects the codec for decoded values
    history <- kv.history[String]("feature.flag")
    _       <- ZIO.debug(s"${history.length} revisions")
  } yield ()
```

Use `consumeKeys` instead of `keys` when the bucket may have thousands of entries.

## Watching for changes

`watch` returns a `ZStream` that never completes on its own — it delivers new changes as they
arrive. Fork it alongside your main program and interrupt it when you are done.

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.kv.*

val watchExample: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")

    // Watch specific keys — delivers current value then all future changes
    // watch emits KvEvent[A]: Put(envelope), Delete(entry), Purge(entry)
    _ <- kv.watch[String](List("feature.flag"))
           .collect { case KvEvent.Put(env) => env.value }
           .tap(v => ZIO.debug(s"New value: $v"))
           .runDrain
           .fork

    // Watch the entire bucket
    _ <- kv.watchAll[String]()
           .tap(e => ZIO.debug(e.toString))
           .runDrain
           .fork

    // Watch with options
    _ <- kv.watch[String](
           List("feature.>"),
           KeyValueWatchOptions(
             ignoreDeletes  = true,   // only deliver Put events (no Delete/Purge)
             includeHistory = true,   // start from the first revision per key
             updatesOnly    = true,   // only deliver new writes (skip current value)
             fromRevision   = Some(42L) // resume from a specific revision
           )
         ).runDrain.fork
  } yield ()
```

**What's happening:**

1. By default, `watch` delivers the current value of the key first, then streams future changes. This makes it suitable for initialising local state from the server and keeping it in sync.
2. `updatesOnly = true` skips the initial delivery — use this when you only care about changes after the watch starts.
3. `ignoreDeletes = true` filters out `Delete` and `Purge` operations.
4. `fromRevision` lets you resume a watch after a restart without missing changes.

## Next steps

- [Object Store guide](./06-object-store.md) — bucket storage for binary blobs
- [JetStream guide](./03-jetstream.md) — KV is built on JetStream; understanding streams helps with advanced config
- [Serialization guide](./02-serialization.md) — store and retrieve typed domain objects
