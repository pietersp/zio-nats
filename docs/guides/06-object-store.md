---
id: object-store
title: Object Store
---

The NATS Object Store provides named binary storage in a bucket, with automatic chunking for arbitrarily large objects. A 10-byte config file and a 2 GB video use the same API - NATS handles splitting and reassembling the chunks transparently. Like the Key-Value store, Object Store is built on JetStream streams and supports metadata, soft deletes, and a watch stream that notifies you when objects change.

## Creating a bucket

A **bucket** is the top-level namespace for an Object Store. Create one with `ObjectStoreManagement` before storing any objects. `ObjectStoreConfig` requires only a `name`; all other fields default to production-ready values. Set `storageType` to `StorageType.Memory` for ephemeral buckets:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val createBucket: ZIO[ObjectStoreManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[ObjectStoreManagement] { osm =>
    osm.create(
      ObjectStoreConfig(
        name          = "assets",
        maxBucketSize = 512L * 1024 * 1024,
        storageType   = StorageType.File
      )
    ).unit
  }
```

## Storing objects

`ObjectStore#put` encodes the value with `NatsCodec[A]`, chunks it server-side, and stores it under a name. `Chunk[Byte]` and `String` work out of the box; for domain types bring a `NatsCodec` in scope (see [Serialization](./02-serialization.md)). `put` returns an `ObjectSummary` with `name`, `size`, `chunks`, and `isDeleted`. Attach a description or custom headers by passing an `ObjectMeta` instead of a plain name.

Three puts - raw image bytes, a plain string, and a typed case class - to show the range of what the same API accepts:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class ImageMetadata(width: Int, height: Int, format: String)
object ImageMetadata { given Schema[ImageMetadata] = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

val store: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Raw bytes - image loaded from disk or another source
    _ <- os.put(
           ObjectMeta("logo.png", description = Some("Brand logo")),
           Chunk.fromArray(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47)) // PNG header bytes
         )

    // Plain string - a README or licence file
    _          <- os.put("README.md", "# Assets\nBrand assets for the shop service.")

    // Typed case class - sidecar metadata stored alongside the image
    _          <- os.put("logo.meta.json", ImageMetadata(512, 512, "PNG"))
  } yield ()
```

For large files, `ObjectStore#putStream` accepts a `ZStream[Any, Nothing, Byte]` and streams bytes directly to the server without buffering the full payload in JVM heap - useful for files, media, or any object large enough to cause memory pressure:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*
import zio.stream.ZStream

val storeStream: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")
    _  <- os.putStream(
             "large-file.bin",
             ZStream.fromChunk(Chunk.fill(1024)(0.toByte))
           ).unit
  } yield ()
```

## Retrieving objects

`ObjectStore#get[A]` reassembles the chunks and decodes them into `ObjectData[A]`. `ObjectData[A]` bundles `data.value` (the decoded payload) and `data.summary` (an `ObjectSummary` with size, chunk count, and delete status). Use `.payload` to drop the wrapper and get just the decoded value:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val retrieve: ZIO[Nats, NatsError, Unit] =
  for {
    os   <- ObjectStore.bucket("assets")
    data <- os.get[Chunk[Byte]]("config.json")
    _    <- ZIO.debug(s"${data.value.length} bytes, deleted=${data.summary.isDeleted}")
    raw  <- os.get[Chunk[Byte]]("config.json").payload
    _    <- ZIO.debug(s"Unwrapped: ${raw.length} bytes")
  } yield ()
```

For large objects, `ObjectStore#getStream` returns a `ZStream[Any, NatsError, Byte]` that downloads chunks on demand. Process the stream with standard ZStream operators without pulling the whole object into memory:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val retrieveStream: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")
    _  <- os.getStream("large-file.bin")
             .grouped(4096)
             .tap(chunk => ZIO.debug(s"Received ${chunk.length} bytes"))
             .runDrain
  } yield ()
```

## Metadata, listing, and links

`ObjectStore#getInfo` returns the `ObjectSummary` for an object without downloading its content - useful for checking size or existence before committing to a download. `ObjectStore#updateMeta` replaces name, description, and headers without re-uploading the bytes. `ObjectStore#list` returns a snapshot of all current objects in the bucket.

`ObjectStore#addLink` creates an alias within the same bucket - reading the alias fetches the bytes of its target. `ObjectStore#addBucketLink` creates a cross-bucket reference so you can resolve objects from another bucket through a single name. To inspect, update, list, and alias objects in one flow:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val metaAndLinks: ZIO[Nats, NatsError, Unit] =
  for {
    os      <- ObjectStore.bucket("assets")
    info    <- os.getInfo("logo.png")
    _       <- ZIO.debug(s"${info.name}: ${info.size} bytes")
    _       <- os.updateMeta("logo.png", ObjectMeta("logo.png", description = Some("Updated logo")))
    objects <- os.list
    _       <- ZIO.foreach(objects)(o => ZIO.debug(o.name))
    _       <- os.addLink("logo-alias", "logo.png")
  } yield ()
```

## Delete and seal

`ObjectStore#delete` marks an object as deleted and sets `isDeleted = true` on its summary. The object is no longer returned by `get`, but its metadata remains accessible via `getInfo` when you pass `includingDeleted = true`.

`ObjectStore#seal` makes the bucket permanently read-only. All subsequent puts fail; gets and streams continue to work. Use a sealed bucket to publish a versioned snapshot that must never be modified - a released software binary, a signed dataset, or an auditable configuration baseline. To soft-delete an object and then seal the bucket:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val deleteAndSeal: ZIO[Nats, NatsError, Unit] =
  for {
    os          <- ObjectStore.bucket("assets")
    _           <- os.delete("old-asset")
    deletedInfo <- os.getInfo("old-asset", includingDeleted = true)
    _           <- ZIO.debug(s"Deleted: ${deletedInfo.isDeleted}")
    _           <- os.seal()
  } yield ()
```

## Watching for changes

`ObjectStore#watch` returns a `ZStream[Any, NatsError, ObjectSummary]` that delivers the current state of every object in the bucket first, then streams all subsequent changes. Each event is an `ObjectSummary` - check `summary.isDeleted` to distinguish a delete event from a new upload. The stream never completes on its own; fork it alongside your program.

Use `watch` to keep a local index of bucket contents in sync with the server, or to trigger downstream processing whenever a new file arrives. `ObjectStoreWatchOptions` controls what the stream delivers:

| Option | Default | Effect |
|--------|---------|--------|
| `ignoreDeletes` | `false` | Suppress events for deleted objects |
| `includeHistory` | `false` | Start from the first revision rather than the current state |
| `updatesOnly` | `false` | Skip the initial current-state delivery; only emit new changes |

To watch for new uploads only, skipping the initial state replay and delete events:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val watching: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")
    _  <- os.watch()
             .tap(s => ZIO.debug(s"${s.name} changed, deleted=${s.isDeleted}"))
             .runDrain
             .fork
    _  <- os.watch(
             ObjectStoreWatchOptions(
               ignoreDeletes = true,
               updatesOnly   = true
             )
           ).tap(s => ZIO.debug(s"New upload: ${s.name}"))
             .runDrain
             .fork
  } yield ()
```

## Next steps

- [Configuration guide](./07-configuration.md) - connecting to authenticated or TLS-secured servers with JetStream enabled
- [JetStream guide](./03-jetstream.md) - Object Store is built on JetStream streams; understanding storage types and retention policies helps with advanced bucket configuration
