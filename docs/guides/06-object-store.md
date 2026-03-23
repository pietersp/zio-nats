---
id: object-store
title: Object Store
---

# Object Store

> Blob storage built on JetStream — large objects, streaming put/get, and change watches.

The **Object Store** stores named binary objects in a bucket. Objects can be arbitrarily large
because they are automatically chunked and stored across multiple JetStream messages. You get
metadata, soft deletes, and a watch stream for changes.

## Prerequisites

- [Quick start](../quickstart.md) completed
- JetStream-enabled NATS server:

```bash
docker run --rm -p 4222:4222 nats -js
```

## Setup

Create a bucket with `ObjectStoreManagement`:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val createBucket: ZIO[ObjectStoreManagement, NatsError, Unit] =
  ZIO.serviceWithZIO[ObjectStoreManagement] { osm =>
    osm.create(ObjectStoreConfig(name = "assets", storageType = StorageType.Memory)).unit
  }

val managementLayer =
  ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> ObjectStoreManagement.live
```

## Basic put and get

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val basicOps: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Put raw bytes — returns ObjectSummary (metadata about the stored object)
    summary <- os.put("config.json", Chunk.fromArray("""{"key":"val"}""".getBytes))
    _       <- ZIO.debug(s"Stored ${summary.size} bytes in ${summary.chunks} chunks")

    // Get with type parameter — returns ObjectData[A] wrapping value + metadata
    data <- os.get[Chunk[Byte]]("config.json")
    _    <- ZIO.debug(s"Got ${data.value.length} bytes")

    // .payload strips the ObjectData wrapper — returns just the decoded value
    raw <- os.get[Chunk[Byte]]("config.json").payload
    _   <- ZIO.debug(s"Raw bytes: ${raw.length}")

    // Put with custom metadata
    _ <- os.put(
           ObjectMeta("logo.png", description = Some("Brand logo")),
           Chunk.fromArray("(binary image data)".getBytes)
         )
  } yield ()
```

**What's happening:**

1. `os.put(name, bytes)` — chunks the bytes and stores them. Returns `ObjectSummary` with `.name`, `.size`, `.chunks`, `.description`, and `.isDeleted`.
2. `os.get[A](name)` — reassembles the chunks and decodes with `NatsCodec[A]`. Returns `ObjectData[A]` with `.value` and `.summary`.
3. `.payload` is an extension on `IO[NatsError, ObjectData[A]]` that strips the wrapper, returning `IO[NatsError, A]`.
4. `ObjectMeta` lets you attach a description, custom headers, and other metadata at write time.

## Streaming put and get

For large objects, stream directly without loading everything into memory:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*
import zio.stream.ZStream

val streamingOps: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Stream from a ZStream[Any, Throwable, Byte]
    _ <- os.putStream("large-file.bin", ZStream.fromChunk(Chunk.fill(1024)(0.toByte)))
           .unit

    // Get as a ZStream[Any, NatsError, Byte]
    _ <- os.getStream("large-file.bin")
           .tap(b => ZIO.unit) // process each byte chunk
           .runDrain
  } yield ()
```

Use `putStream`/`getStream` for files, media, or any payload that should not be buffered
entirely in JVM heap.

## Metadata operations

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val metaOps: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Get metadata without downloading the object
    info <- os.getInfo("logo.png")
    _    <- ZIO.debug(s"Size: ${info.size}, deleted: ${info.isDeleted}")

    // Get info including soft-deleted objects
    deletedInfo <- os.getInfo("logo.png", includingDeleted = true)

    // Update metadata in-place (does not re-upload the object)
    _ <- os.updateMeta("logo.png", ObjectMeta("logo.png", description = Some("Updated logo")))

    // List all objects in the bucket
    objects <- os.list
    _       <- ZIO.foreach(objects)(o => ZIO.debug(o.name))

    // Bucket status — entry count, bytes, stream info
    status <- os.getStatus
    _      <- ZIO.debug(s"Bucket: ${status.bucketName}")
  } yield ()
```

## Delete and seal

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val deleteAndSeal: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Soft delete — marks as deleted but preserves the entry in history
    summary <- os.delete("old-asset")

    // Seal — makes the bucket read-only permanently
    _ <- os.seal().unit
  } yield ()
```

A sealed bucket rejects all further puts but continues to serve gets. Use it to publish a
versioned snapshot that should never change.

## Links

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val linksExample: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Alias — "alias" resolves to "logo.png" in the same bucket
    _ <- os.addLink("alias", "logo.png").unit

    // Cross-bucket link — "mirror" resolves to another ObjectStore
    other <- ObjectStore.bucket("other-bucket")
    _     <- os.addBucketLink("mirror", other).unit
  } yield ()
```

## Watching for changes

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.objectstore.*

val watchExample: ZIO[Nats, NatsError, Unit] =
  for {
    os <- ObjectStore.bucket("assets")

    // Watch all changes — delivers current entries then future changes
    _ <- os.watch()
           .tap(summary => ZIO.debug(s"Changed: ${summary.name}"))
           .runDrain
           .fork

    // Watch with options
    _ <- os.watch(ObjectStoreWatchOptions(
           ignoreDeletes  = true,
           includeHistory = false,
           updatesOnly    = true
         )).runDrain.fork
  } yield ()
```

The watch stream never completes on its own. Fork it alongside your program.

## Next steps

- [Key-Value guide](./05-key-value.md) — KV store for smaller, structured data
- [JetStream guide](./03-jetstream.md) — Object Store is built on JetStream streams
- [Serialization guide](./02-serialization.md) — decode objects directly into domain types
