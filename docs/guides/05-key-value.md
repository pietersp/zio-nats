---
id: key-value
title: Key-Value Store
---

The NATS Key-Value store is a named bucket of key-value pairs where every write creates a new revision. The full history of each key is preserved up to a configurable limit, giving you versioned storage with the ability to read past values, watch for changes in real time, and resume a watch after a restart without missing updates. KV is built on JetStream streams under the hood - the same persistence, replication, and delivery guarantees apply.

## Creating a bucket

A **bucket** is the top-level namespace for a KV store. Each bucket is independent and must be created on the server before any reads or writes. Use `KeyValueManagement` to create and manage buckets. The `name` field is the only required field; everything else has a sensible default. Use `StorageType.Memory` for ephemeral data (fast, non-durable) and `StorageType.File` (the default) for persistence across server restarts:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val createBucket: ZIO[KeyValueManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[KeyValueManagement] { kvm =>
    kvm.create(
      KeyValueConfig(
        name             = "config",
        storageType      = StorageType.Memory,
        maxHistoryPerKey = 10,
        ttl              = Some(24.hours)
      )
    ).unit
  }
```

`maxHistoryPerKey` controls how many revisions per key the server retains - once exceeded, the oldest revision is dropped. `ttl` sets a default expiry for all entries in the bucket; individual writes can override this with a per-entry TTL when calling `create`.

## Reads and writes

`KeyValue.bucket` returns a `KeyValue` handle for an existing bucket. `put` encodes the value with `NatsCodec[A]` and returns the revision number the server assigned. `get` returns the current value wrapped in `Option[KvEnvelope[A]]` - `None` if the key has never been written or was purged. `KvEnvelope[A]` bundles `env.value` (the decoded payload), `env.entry.revision` (the revision number), and `env.entry.key` (the key name).

`A` can be any type with a `NatsCodec` in scope - a plain `String`, `Chunk[Byte]`, or a domain type like `FeatureConfig` with a derived codec (see [Serialization](./02-serialization.md)). Write and read a feature flag:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val readWrite: ZIO[Nats, NatsError, Unit] =
  for {
    kv  <- KeyValue.bucket("config")
    rev <- kv.put("feature.dark-mode", "true")
    _   <- ZIO.debug(s"Written at revision $rev")
    env <- kv.get[String]("feature.dark-mode")
    _   <- ZIO.foreach(env)(e =>
             ZIO.debug(s"${e.entry.key} = ${e.value} (rev ${e.entry.revision})")
           )
  } yield ()
```

`delete` places a tombstone marker on a key and preserves all previous history. Subsequent `get` calls return `None`, but the history remains accessible via `history`. `purge` removes all history including the tombstone, permanently erasing the key from the bucket:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val deleteAndPurge: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")
    _  <- kv.put("temp.key", "value")
    _  <- kv.delete("temp.key")  // tombstone placed; history preserved
    _  <- kv.purge("old.key")    // all history removed
  } yield ()
```

## Optimistic concurrency

`create` writes a value only if the key does not currently exist - it fails if the key is already present or has a tombstone from a previous `delete`. `update` writes only if the stored revision matches `expectedRevision`, failing if another writer modified the key between your `get` and your `update`.

These two operations together give you compare-and-swap semantics over NATS - useful for distributed locking, idempotent initialisation, or any shared-state update that must not silently overwrite a concurrent write:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val cas: ZIO[Nats, NatsError, Unit] =
  for {
    kv  <- KeyValue.bucket("config")

    // Fails if the key already exists
    rev <- kv.create("lock.checkout", "worker-1")

    // Fails if the stored revision is not `rev`
    _   <- kv.update("lock.checkout", "worker-2", expectedRevision = rev)

    // Per-entry TTL overrides the bucket default for this key only
    _   <- kv.create("lease.session-42", "active", ttl = Some(30.seconds))
  } yield ()
```

## Revisions and history

Every `put`, `create`, and `update` increments the revision counter for that key. `get` accepts an optional `revision` parameter to read any retained past value by its exact revision number. `history` returns all retained revisions in chronological order, up to the `maxHistoryPerKey` limit set when the bucket was created:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val revisions: ZIO[Nats, NatsError, Unit] =
  for {
    kv   <- KeyValue.bucket("config")
    _    <- kv.put("threshold", "100")
    _    <- kv.put("threshold", "200")
    old  <- kv.get[String]("threshold", revision = Some(1L))
    all  <- kv.history[String]("threshold")
    _    <- ZIO.debug(s"${all.length} revisions; first was ${old.map(_.value)}")
  } yield ()
```

## Listing keys

`keys` loads all current key names into a `List[String]` in one shot. Pass a filter list using NATS wildcard syntax (`*` for one token, `>` for one-or-more trailing tokens) to scope the result to a subset of keys. For large buckets, prefer `consumeKeys` - it returns a `ZStream[Any, NatsError, String]` and emits keys incrementally without loading the full set into memory:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val listing: ZIO[Nats, NatsError, Unit] =
  for {
    kv   <- KeyValue.bucket("config")
    all  <- kv.keys()
    feat <- kv.keys(List("feature.*"))
    _    <- ZIO.debug(s"${all.length} total, ${feat.length} feature flags")
    _    <- kv.consumeKeys(List("feature.*"))
               .tap(k => ZIO.debug(k))
               .runDrain
  } yield ()
```

## Watching for changes

`watch` returns a `ZStream[Any, NatsError, KvEvent[A]]` that never completes on its own. By default it replays the current value of each matching key first, then streams all subsequent changes - a natural fit for loading initial state and keeping it live without a separate `get` call at startup. `KvEvent` is a sealed type with three cases: `KvEvent.Put(envelope)` for writes, `KvEvent.Delete(entry)` for soft deletes, and `KvEvent.Purge(entry)` for history purges.

Watch all feature flags and react to each new value, filtering out deletions with `collect`:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val liveFlags: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")
    _  <- kv.watch[String](List("feature.>"))
             .collect { case KvEvent.Put(env) => env.value }
             .tap(v => ZIO.debug(s"Flag updated: $v"))
             .runDrain
             .fork
  } yield ()
```

`KeyValueWatchOptions` controls what the stream delivers and where it starts:

| Option | Default | Effect |
|--------|---------|--------|
| `ignoreDeletes` | `false` | Suppress `Delete` and `Purge` events |
| `includeHistory` | `false` | Start from the first retained revision per key rather than the current value |
| `updatesOnly` | `false` | Skip the initial current-value delivery; only emit new changes after the watch starts |
| `fromRevision` | `None` | Resume from a specific revision - useful for restarting without reprocessing |

Resume a watch from a known revision to pick up exactly where a previous session left off:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.kv.*

val resumeWatch: ZIO[Nats, NatsError, Unit] =
  for {
    kv <- KeyValue.bucket("config")
    _  <- kv.watchAll[String](
             KeyValueWatchOptions(
               fromRevision  = Some(100L),
               ignoreDeletes = true
             )
           ).tap(e => ZIO.debug(e.toString))
             .runDrain
             .fork
  } yield ()
```

## Next steps

- [Object Store guide](./06-object-store.md) - bucket storage for larger binary objects
- [JetStream guide](./03-jetstream.md) - KV is built on JetStream; understanding streams and retention policies helps with advanced bucket configuration
