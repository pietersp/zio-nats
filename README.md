# zio-nats

A ZIO 2 wrapper for the [jnats](https://github.com/nats-io/nats.java) NATS client.

![Scala 3](https://img.shields.io/badge/scala-3-blue)
![ZIO 2](https://img.shields.io/badge/ZIO-2-red)
![License](https://img.shields.io/badge/license-Apache--2.0-green)

- Idiomatic ZIO 2 services with `ZLayer` for every subsystem
- Full API coverage: core pub/sub, JetStream, Key-Value, Object Store
- `ZStream`-based subscriptions and consumers — no callbacks in user code
- Typed error model (`NatsError` sealed ADT)
- Type-safe serialization — built-in `Chunk[Byte]`/`String` codecs; [zio-blocks](https://zio.dev/zio-blocks) integration included by default
- Zero raw jnats types in user code — `import zio.nats.*` is all you need
- Scala 3

## Installation

Add to `build.sbt`:

```scala
// ── Option A: batteries-included ────────────────────────────────────────────
// Includes pub/sub, JetStream, KV, Object Store, Service Framework,
// and zio-blocks type-safe serialization.
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "<version>"

// ── Option B: core only (no zio-blocks) ─────────────────────────────────────
// Use this when you want jsoniter-scala or a fully custom NatsCodec[A]
// instead of zio-blocks.
libraryDependencies += "io.github.pietersp" %% "zio-nats-core" % "<version>"

// ── Optional add-on: jsoniter-scala serialization ───────────────────────────
// Pair with zio-nats-core (instead of zio-blocks) or add alongside zio-nats
// (to use jsoniter for selected types while keeping zio-blocks for others).
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "<version>"

// ── Optional add-on: play-json serialization ─────────────────────────────────
// Pair with zio-nats-core (instead of zio-blocks) or add alongside zio-nats
// (to use play-json for selected types while keeping zio-blocks for others).
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "<version>"

// ── Testkit ──────────────────────────────────────────────────────────────────
// Integration test helpers — starts a NATS container via testcontainers.
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "<version>" % Test
```


## Quick start

Requires a running NATS server (`nats-server`).

```scala
import zio._
import zio.nats._
import zio.nats.config.NatsConfig

object Main extends ZIOAppDefault {
  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats  <- ZIO.service[Nats]
      fiber <- nats.subscribe[String](Subject("greetings"))
                 .take(3)
                 .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                 .runDrain
                 .fork
      _     <- ZIO.sleep(200.millis)
      _     <- ZIO.foreachDiscard(1 to 3) { i =>
                 nats.publish(Subject("greetings"), s"Hello #$i")
               }
      _     <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(NatsConfig.live, Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
```

> `Subject` and `QueueGroup` are opaque types from `import zio.nats._` — no additional import needed.
> `nats.subscribe[String]` decodes each payload as UTF-8 and wraps it in an `Envelope[String]`. Use `Chunk[Byte]` instead to receive raw bytes, with `env.message` giving access to headers, subject, and reply-to.

## Core concepts

`Nats` is the root service. All other services are derived from it:

```
NatsConfig
    └── Nats.live              ← core connection (pub/sub/request-reply)
            ├── JetStream.live              ← JetStream publishing + consumer access
            ├── JetStreamManagement.live    ← stream + consumer admin
            ├── KeyValueManagement.live     ← KV bucket admin
            ├── KeyValue.live(name)         ← KV bucket service (ZLayer)
            ├── ObjectStoreManagement.live  ← Object Store admin
            └── ObjectStore.live(name)      ← Object Store bucket service (ZLayer)
```

`KeyValue.live(name)` and `ObjectStore.live(name)` are `ZLayer`s — the idiomatic way to wire a bucket service into an application's dependency graph. `KeyValue.bucket(name)` and `ObjectStore.bucket(name)` remain available as `ZIO` effects for programmatic use inside a for-comprehension.

All services are wired via `ZLayer`. Use `>+>` to keep all prior services in scope:

```scala
val appLayer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStream.live >+>
  JetStreamManagement.live >+>
  KeyValueManagement.live
```

## Pub/Sub & Request-Reply

### Publish

```scala
// nats: Nats obtained via ZIO.service[Nats]

// UTF-8 string (built-in NatsCodec[String])
nats.publish(Subject("events.user"), "payload")

// Raw bytes (built-in NatsCodec[Chunk[Byte]])
nats.publish(Subject("events.user"), bytes)

// With headers
nats.publish(
  Subject("events.user"),
  bytes,
  PublishParams(headers = Headers("Content-Type" -> "application/json"))
)

// With reply-to
nats.publish(
  Subject("events.user"),
  bytes,
  PublishParams(replyTo = Some(Subject("_INBOX.reply")))
)
```

`Headers` supports multi-value headers and merging:

```scala
val h = Headers("X-Trace" -> "abc", "Content-Type" -> "application/json")
h.get("X-Trace")        // Chunk("abc")
h.add("X-Trace", "def") // Headers with Chunk("abc", "def") for X-Trace
```

### Subscribe

`subscribe[A]` returns a `ZStream` of `Envelope[A]`. The underlying jnats `Dispatcher` is created when the stream is consumed and closed automatically when the stream is interrupted. Every `Envelope` carries both the decoded value and the raw `NatsMessage`:

```scala
// nats: Nats obtained via ZIO.service[Nats]

// Typed String — payload decoded as UTF-8 via built-in NatsCodec[String]
nats.subscribe[String](Subject("events.>"))
  .tap(env => ZIO.debug(s"${env.message.subject}: ${env.value}"))
  .runDrain

// Raw bytes — identity codec, full NatsMessage available via env.message
nats.subscribe[Chunk[Byte]](Subject("events.>"))
  .tap(env => ZIO.debug(s"${env.message.subject}: ${env.message.dataAsString}"))
  .runDrain
```

### Queue groups

Messages are load-balanced across all subscribers in the same queue group:

```scala
// Typed — decoded payload in Envelope
nats.subscribe[WorkItem](Subject("work.queue"), QueueGroup("workers"))
  .map(_.value)
  .tap(item => process(item))
  .runDrain

// Raw bytes
nats.subscribe[Chunk[Byte]](Subject("work.queue"), QueueGroup("workers"))
  .tap(env => process(env.message))
  .runDrain
```

### Request-Reply

```scala
// Returns Envelope[B] — decoded value + raw NatsMessage metadata
val reply: IO[NatsError, Envelope[Chunk[Byte]]] =
  nats.request[Chunk[Byte], Chunk[Byte]](Subject("rpc.add"), payload, timeout = 5.seconds)

// .payload strips the Envelope wrapper, returning only the decoded value
val value: IO[NatsError, Chunk[Byte]] =
  nats.request[Chunk[Byte], Chunk[Byte]](Subject("rpc.add"), payload, timeout = 5.seconds).payload
```

## Type-Safe Serialization (zio-blocks)

zio-nats supports type-safe publish/subscribe using [zio-blocks Schema](https://zio.dev/zio-blocks). Provide a `given Schema[T]` and a `NatsCodec` derived from a format, and the library handles serialization automatically.

### Setup

Define schemas for your types and install a codec builder:

```scala
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class Person(name: String, age: Int)
object Person {
  given schema: Schema[Person] = Schema.derived
}

// Build a NatsCodec for all Schema-annotated types using JSON.
val codecs = NatsCodec.fromFormat(JsonFormat)

// The codec for Person is derived eagerly here — at this import site —
// not buried inside the first publish call. If Person has no Schema, or
// the format cannot derive a codec for it, an exception is thrown
// immediately at startup, before any message is sent.
import codecs.derived  // NatsCodec[Person] is now in scope
```

### Codec construction guarantees

The `import codecs.derived` line is where the codec is compiled and cached. Two consequences:

1. **Fail-fast** — a missing `Schema`, unsupported type, or incompatible format surfaces immediately at the `import` site, not inside the first `publish` call during production load.
2. **Safe encode** — once successfully constructed, `NatsCodec[A].encode(value)` calls a pre-built codec and never re-derives. Any serialization failure during publishing surfaces as a typed `NatsError.SerializationError` in the ZIO error channel, not as a JVM exception or untyped defect.

### Type-Safe Publish

```scala
// nats: Nats obtained via ZIO.service[Nats]

// Encoding is handled by the pre-built NatsCodec[Person].
// Failures (e.g. OOM inside the serializer) surface as NatsError.SerializationError.
nats.publish(Subject("users"), Person("Alice", 30))
```

### Type-Safe Subscribe

`subscribe[A]` returns a `ZStream` of `Envelope[A]`. Each `Envelope` holds the decoded value and the raw `NatsMessage` (headers, subject, reply-to).

```scala
// nats: Nats obtained via ZIO.service[Nats]

// Access decoded value via .value
nats.subscribe[Person](Subject("users"))
  .map(_.value)
  .tap(person => ZIO.debug(s"Got: ${person.name}"))
  .runDrain

// Or use the .payload extension to strip the Envelope from the whole stream
nats.subscribe[Person](Subject("users")).payload
  .tap(person => ZIO.debug(s"Got: ${person.name}"))
  .runDrain

// Keep the full Envelope for header access
nats.subscribe[Person](Subject("users"))
  .tap(env => ZIO.debug(s"Headers: ${env.message.headers}, Person: ${env.value}"))
  .runDrain
```

Queue-group subscriptions also support typed decoding:

```scala
nats.subscribe[Order](Subject("orders"), QueueGroup("processors")).payload
  .tap(order => ZIO.debug(s"order: ${order.id}"))
  .runDrain
```

### JetStream Type-Safe Publish

```scala
js.publish(Subject("events"), Event("click", timestamp))
```

### Per-type codec override

Multiple formats can coexist. Override for a specific type with a plain `given`:

```scala
given auditCodec: NatsCodec[AuditEvent] =
  NatsCodec.fromFormat(BsonFormat).derived[AuditEvent]
```

### Available formats

`zio-nats-zio-blocks` brings in `zio-blocks-schema` (JSON) transitively. For other formats add the corresponding artifact alongside it:

```scala
// build.sbt — add format artifacts as needed alongside zio-nats
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"     % "0.0.29"    // Avro
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"     % "0.0.29"    // Toon
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack"  % "0.0.29"    // MessagePack
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"   % "0.0.29"    // Thrift
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"     % "0.0.29"    // BSON
```

```scala
import zio.blocks.schema.json.JsonFormat
import zio.blocks.schema.avro.AvroFormat
import zio.blocks.schema.toon.ToonFormat

val jsonCodecs  = NatsCodec.fromFormat(JsonFormat)
val avroCodecs  = NatsCodec.fromFormat(AvroFormat)
```

## Type-Safe Serialization (jsoniter-scala)

As an alternative (or complement) to zio-blocks, `zio-nats-jsoniter` integrates with
[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) for high-performance JSON
serialization.

Add the dependency — choose the base artifact that matches your setup:

```scala
// Replace zio-blocks entirely: core + jsoniter
libraryDependencies += "io.github.pietersp" %% "zio-nats-core"    % "<version>"
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "<version>"

// Or add jsoniter alongside zio-blocks (selected types use jsoniter, rest use zio-blocks)
libraryDependencies += "io.github.pietersp" %% "zio-nats"          % "<version>"
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "<version>"
```

### Automatic bridging (recommended)

Place a `given JsonValueCodec[A]` in scope and `import zio.nats.*`. The top-level
`given fromJsonValueCodec` bridges it to `NatsCodec[A]` automatically — no builder step required.
A `NotGiven[NatsCodec[A]]` guard ensures it never shadows built-in codecs (`String`,
`Chunk[Byte]`) or any explicit `given NatsCodec[A]` you provide:

```scala
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import zio.nats.*

case class Person(name: String, age: Int)
object Person {
  given JsonValueCodec[Person] = JsonCodecMaker.make
}

// NatsCodec[Person] is resolved automatically — just use the service:
nats.publish(Subject("persons"), Person("Alice", 30))
nats.subscribe[Person](Subject("persons")).payload
  .tap(p => ZIO.debug(s"Got: ${p.name}"))
  .runDrain
```

### Explicit one-off codec

Use `NatsCodecJsoniter.fromJsoniter` when you need a codec without putting one into implicit scope:

```scala
val codec: NatsCodec[Person] = NatsCodecJsoniter.fromJsoniter(JsonCodecMaker.make[Person])
```

### Coexistence with zio-blocks

Both integrations can be used in the same project. Per-type overrides are plain `given val`s:

```scala
// Most types use zio-blocks:
val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived

// One type uses jsoniter instead:
given JsonValueCodec[FastEvent] = JsonCodecMaker.make
// NatsCodec[FastEvent] resolved via fromJsonValueCodec; other types use zio-blocks
```

## Type-Safe Serialization (play-json)

As an alternative (or complement) to zio-blocks, `zio-nats-play-json` integrates with
[play-json](https://github.com/playframework/play-json) for JSON serialization.

Add the dependency — choose the base artifact that matches your setup:

```scala
// Replace zio-blocks entirely: core + play-json
libraryDependencies += "io.github.pietersp" %% "zio-nats-core"     % "<version>"
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "<version>"

// Or add play-json alongside zio-blocks (selected types use play-json, rest use zio-blocks)
libraryDependencies += "io.github.pietersp" %% "zio-nats"           % "<version>"
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "<version>"
```

### Automatic bridging (recommended)

Place a `given Format[A]` in scope and `import zio.nats.*`. The top-level
`given fromPlayJsonFormat` bridges it to `NatsCodec[A]` automatically — no builder step required.
Same `NotGiven[NatsCodec[A]]` guard applies — built-in and explicit codecs always win:

```scala
import play.api.libs.json.{Format, Json}
import zio.nats.*

case class Person(name: String, age: Int)
object Person {
  given Format[Person] = Json.format[Person]
}

// NatsCodec[Person] is resolved automatically — just use the service:
nats.publish(Subject("persons"), Person("Alice", 30))
nats.subscribe[Person](Subject("persons")).payload
  .tap(p => ZIO.debug(s"Got: ${p.name}"))
  .runDrain
```

### Explicit one-off codec

Use `NatsCodecPlayJson.fromPlayJson` when you need a codec without putting one into implicit scope:

```scala
val codec: NatsCodec[Person] = NatsCodecPlayJson.fromPlayJson(Json.format[Person])
```

## JetStream

Requires JetStream-enabled NATS: `nats-server -js` or `docker run -p 4222:4222 nats -js`.

### Publishing

```scala
// given NatsCodec[Order] in scope
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >>> JetStream.live

val publish =
  for {
    js  <- ZIO.service[JetStream]
    ack <- js.publish(Subject("orders.new"), order)
    _   <- Console.printLine(s"seq=${ack.seqno}")
  } yield ()
```

Publish with duplicate detection via message ID, using `JsPublishParams`:

```scala
val ack = js.publish(
  Subject("orders.new"),
  order,
  JsPublishParams(options = Some(PublishOptions(messageId = Some("order-42"))))
)
```

Publish with headers:

```scala
val ack = js.publish(
  Subject("orders.new"),
  order,
  JsPublishParams(headers = Headers("X-Source" -> "checkout"))
)
```

Publish asynchronously (fire-and-forget, collect acks later):

```scala
// Returns IO[NatsError, Task[PublishAck]] — the inner Task resolves when the server acks
val futureAck: IO[NatsError, Task[PublishAck]] =
  js.publishAsync(Subject("orders.new"), order)
```

### Management

Create streams and consumers using `JetStreamManagement`. All config types are plain Scala case classes — no builders required:

```scala
val layer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStreamManagement.live

val setup = for {
  jsm <- ZIO.service[JetStreamManagement]
  _   <- jsm.addStream(
           StreamConfig(
             name        = "ORDERS",
             subjects    = List("orders.>"),
             storageType = StorageType.Memory
           )
         )
  _   <- jsm.addOrUpdateConsumer(
           "ORDERS",
           ConsumerConfig.durable("processor").copy(
             filterSubject = Some("orders.>"),
             ackPolicy     = AckPolicy.Explicit
           )
         )
} yield ()
```

### Consuming — durable Consumer

Get a `Consumer` handle from `JetStream.consumer`, then use its methods directly:

```scala
for {
  js       <- ZIO.service[JetStream]
  consumer <- js.consumer("ORDERS", "processor")

  // Bounded fetch — each env.value is a decoded Order; env.message holds ack ops
  _ <- consumer.fetch[Order](FetchOptions(maxMessages = 10, expiresIn = 5.seconds))
         .mapZIO(env => process(env.value) *> env.message.ack)
         .runDrain

  // Indefinite push-style consume
  _ <- consumer.consume[Order]()
         .mapZIO(env => process(env.value) *> env.message.ack)
         .runDrain

  // Pull-based iterate (retries on empty polls)
  _ <- consumer.iterate[Order]()
         .mapZIO(env => process(env.value) *> env.message.ack)
         .runDrain

  // Single next message (returns None if no message within timeout)
  env <- consumer.next[Order](5.seconds)
} yield ()
```

All four methods are generic `[A: NatsCodec]` and return `JsEnvelope[A]`, which bundles:
- `env.value` — the decoded domain type
- `env.message` — the raw `JetStreamMessage` for ack/nak/term/inProgress and metadata

### Consuming — OrderedConsumer

An ordered consumer guarantees strict in-order delivery and automatically re-creates itself on reconnect or sequence gaps. Unlike a durable consumer it requires no server-side state.

```scala
for {
  js      <- ZIO.service[JetStream]
  ordered <- js.orderedConsumer("EVENTS", OrderedConsumerConfig(
               filterSubjects = List("events.>"),
               deliverPolicy  = Some(DeliverPolicy.All)
             ))
  _ <- ordered.consume[MyEvent]()
         .tap(env => ZIO.debug(env.value.toString))
         .runDrain
} yield ()
```

`OrderedConsumer` exposes the same `fetch[A]` / `consume[A]` / `iterate[A]` / `next[A]` methods as `Consumer`.

#### Ack methods on `JetStreamMessage`

| Method                 | Effect                                        |
|------------------------|-----------------------------------------------|
| `msg.ack`              | Acknowledge successful processing             |
| `msg.ackSync(timeout)` | Ack and wait for server confirmation          |
| `msg.nak`              | Request redelivery immediately                |
| `msg.nakWithDelay(d)`  | Request redelivery after delay                |
| `msg.term`             | Terminate — do not redeliver                  |
| `msg.inProgress`       | Extend ack deadline (work-in-progress signal) |

`msg.decode[A]` decodes the payload using the given `NatsCodec[A]`.

## Key-Value store

### Setup

```scala
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> KeyValueManagement.live

val createBucket = (for {
  kvm <- ZIO.service[KeyValueManagement]
  _   <- kvm.create(KeyValueConfig(name = "config", storageType = StorageType.Memory))
} yield ()).provide(layer)
```

### Operations

```scala
// Obtain a bucket handle (requires Nats in the environment)
val kv: ZIO[Nats, NatsError, KeyValue] = KeyValue.bucket("config")

// Put — returns the new revision number
kv.put("feature.flag", "true")   // IO[NatsError, Long]
kv.put("payload", bytes)

// Get — returns KeyValueEntry or None
kv.get("feature.flag")                     // IO[NatsError, Option[KeyValueEntry]]
kv.get("feature.flag", revision = 3L)      // get by revision

// Compare-and-swap
kv.create("lock", bytes)                               // create-only (fails if key exists)
kv.create("lease", bytes, ttl = Some(30.seconds))      // create with per-entry TTL
kv.update("lock", newBytes, expectedRevision = 3)      // update only if revision matches

// Delete / purge
kv.delete("stale-key")                                 // soft delete — history preserved
kv.delete("stale-key", expectedRevision = Some(5))     // conditional delete
kv.purge("old-key")                                    // remove all history for the key
kv.purge("old-key", expectedRevision = Some(5))        // conditional purge
kv.purge("old-key", markerTtl = Some(5.minutes))       // purge with TTL on tombstone marker

// Enumerate — eagerly loads all keys
kv.keys()                       // IO[NatsError, List[String]]
kv.keys(List("foo.*"))          // filter by subject pattern
kv.keys(List("foo.*", "bar.*")) // multiple filters

// Enumerate — streaming (memory-efficient for large buckets)
kv.consumeKeys()                        // ZStream[Any, NatsError, String]
kv.consumeKeys(List("foo.*"))
kv.consumeKeys(List("foo.*", "bar.*"))

kv.history("key")   // IO[NatsError, List[KeyValueEntry]]

// Purge delete/purge markers
kv.purgeDeletes()                             // removes markers older than 30 minutes
kv.purgeDeletes(threshold = Some(1.hour))     // custom threshold
```

### Watch for changes

```scala
// Watch a single key — never completes unless interrupted
kv.watch("feature.flag")   // ZStream[Any, NatsError, KeyValueEntry]
kv.watchAll()              // watch entire bucket

// Watch with options
kv.watch("feature.>", KeyValueWatchOptions(
  ignoreDeletes   = true,  // skip delete/purge markers
  includeHistory  = true,  // start from first entry per key (not just latest)
  updatesOnly     = true,  // only deliver new writes after watch starts
  fromRevision    = Some(42L)  // resume from a specific revision
))
kv.watchAll(KeyValueWatchOptions(metaOnly = true))  // headers only, skip values
```

`KeyValueEntry` fields: `.key`, `.value: Chunk[Byte]`, `.revision: Long`, `.operation: KeyValueOperation`, `.bucketName`, `.valueAsString` (UTF-8 decode), `.decode[A]` (typed decode).

## Object Store

```scala
// Management
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> ObjectStoreManagement.live

val createBucket = (for {
  osm <- ZIO.service[ObjectStoreManagement]
  _   <- osm.create(ObjectStoreConfig(name = "assets", storageType = StorageType.Memory))
} yield ()).provide(layer)

// Obtain a bucket handle (requires Nats in the environment)
val os: ZIO[Nats, NatsError, ObjectStore] = ObjectStore.bucket("assets")

// Put / Get — returns ObjectData[A] wrapping the decoded value + ObjectSummary metadata
os.put("config.json", configBytes)           // IO[NatsError, ObjectSummary]
os.get[Chunk[Byte]]("config.json")           // IO[NatsError, ObjectData[Chunk[Byte]]]
os.get[MyConfig]("config.json")             // IO[NatsError, ObjectData[MyConfig]]
os.get[MyConfig]("config.json").payload     // IO[NatsError, MyConfig]  (strips wrapper)

// With custom metadata
os.put(ObjectMeta("logo.png", description = Some("Brand logo")), imageBytes)

// Streaming put/get — avoids loading the full object into memory
os.putStream("video.mp4", ZStream.fromFile(path))    // IO[NatsError, ObjectSummary]
os.getStream("video.mp4")                            // ZStream[Any, NatsError, Byte]

// Metadata
os.getInfo("logo.png")                       // IO[NatsError, ObjectSummary]
os.getInfo("logo.png", includingDeleted = true)

// Mutation
os.updateMeta("logo.png", ObjectMeta("logo.png", description = Some("Updated")))
os.delete("old-asset")                       // IO[NatsError, ObjectSummary] — soft delete

// Links
os.addLink("alias", "logo.png")              // link to another object in this bucket
os.addBucketLink("mirror", otherStore)       // link to another ObjectStore bucket

// Seal (make read-only)
os.seal()                                    // IO[NatsError, ObjectStoreBucketStatus]

os.list                                      // IO[NatsError, List[ObjectSummary]]
os.getStatus                                 // IO[NatsError, ObjectStoreBucketStatus]

// Watch for changes
os.watch()                                   // ZStream[Any, NatsError, ObjectSummary]
os.watch(ObjectStoreWatchOptions(
  ignoreDeletes  = true,
  includeHistory = true,
  updatesOnly    = true
))
```

`ObjectSummary` fields: `.name`, `.size: Long`, `.chunks: Long`, `.description: Option[String]`, `.isDeleted: Boolean`.

`ObjectData[A]` fields: `.value: A` (decoded payload), `.summary: ObjectSummary` (metadata). Use the `.payload` extension to strip the wrapper: `os.get[A]("key").payload`.

## Connection Events

`Nats.lifecycleEvents` is a `ZStream[Any, Nothing, NatsEvent]` that emits connection state changes. Fork it alongside your program:

```scala
for {
  nats <- ZIO.service[Nats]
  _    <- nats.lifecycleEvents
            .tap(e => ZIO.logInfo(s"[nats] $e"))
            .runDrain
            .fork
  _    <- program
} yield ()
```

Filter to specific events:

```scala
nats.lifecycleEvents
  .collect { case NatsEvent.Disconnected(url) => url }
  .tap(url => ZIO.logWarning(s"Disconnected from $url"))
  .runDrain
  .fork
```

Event ADT:

| Event                    | When                               |
|--------------------------|------------------------------------|
| `Connected(url)`         | Initial connection established     |
| `Disconnected(url)`      | Connection lost                    |
| `Reconnected(url)`       | Reconnection successful            |
| `ServersDiscovered(url)` | New cluster route discovered       |
| `Closed`                 | Connection permanently closed      |
| `LameDuckMode`           | Server entering lame-duck shutdown |
| `Error(message)`         | Non-fatal error string from server |
| `ExceptionOccurred(ex)`  | Exception from the client          |

## Connection utilities

```scala
nats.status                        // URIO[Nats, ConnectionStatus]
nats.serverInfo                    // IO[NatsError, NatsServerInfo]
nats.rtt                           // IO[NatsError, Duration]
nats.connectedUrl                  // URIO[Nats, Option[String]]
nats.statistics                    // URIO[Nats, ConnectionStats]
nats.outgoingPendingMessageCount   // URIO[Nats, Long]
nats.outgoingPendingBytes          // URIO[Nats, Long]
nats.flush(timeout = 1.second)     // IO[NatsError, Unit]
nats.drain(timeout = 30.seconds)   // IO[NatsError, Unit]
```

`ConnectionStats` fields: `.inMsgs`, `.outMsgs`, `.inBytes`, `.outBytes`, `.reconnects`, `.droppedCount`, and more.

## Error handling

All operations return `IO[NatsError, A]`. `NatsError` is a sealed trait with exhaustive pattern matching:

```scala
import zio.nats.NatsError._

nats.publish(Subject("subject"), bytes).catchAll {
  case ConnectionClosed(msg)                    => ZIO.logError(s"Connection closed: $msg")
  case Timeout(msg)                             => ZIO.logWarning(s"Timed out: $msg")
  case SerializationError(msg, _)               => ZIO.logError(s"Encode failed: $msg")
  case JetStreamApiError(msg, code, apiCode, _) => ZIO.logError(s"JetStream API $code/$apiCode: $msg")
  case KeyNotFound(key)                         => ZIO.logInfo(s"Key $key not found")
  case DecodingError(msg, _)                    => ZIO.logError(s"Decode failed: $msg")
  case other                                    => ZIO.logError(other.message)
}
```

Sub-sealed traits for domain grouping:

- `NatsError.JetStreamError` — all JetStream errors
- `NatsError.KeyValueError` — includes `KeyNotFound`
- `NatsError.ObjectStoreError`

## Testing

Add the testkit dependency:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "<version>" % Test
```

`NatsTestLayers.nats` starts a NATS container (via testcontainers) and provides a `Nats` service wired to it. Use `.provideShared` to start the container once per suite:

```scala
import zio.test._
import zio.test.TestAspect._
import zio.nats.testkit.NatsTestLayers

object MySpec extends ZIOSpecDefault {
  def spec = suite("MySpec")(
    test("publishes and receives") {
      for {
        nats  <- ZIO.service[Nats]
        fiber <- nats.subscribeRaw(Subject("t")).take(1).runCollect.fork
        _     <- ZIO.sleep(200.millis)
        _     <- nats.publish(Subject("t"), "hi")
        msgs  <- fiber.join
      } yield assertTrue(msgs.head.dataAsString == "hi")
    }
  ).provideShared(NatsTestLayers.nats) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
```

> **Podman / WSL users:** Set `DOCKER_HOST=unix:///tmp/podman/podman-machine-default-api.sock`
> and `TESTCONTAINERS_RYUK_DISABLED=true` in your test environment.

The testcontainer is started with `--js` so JetStream, KV, and Object Store APIs are all available in tests.

## NatsConfig reference

```scala
NatsConfig(
  servers              = List("nats://localhost:4222"),
  connectionName       = None,
  connectionTimeout    = 2.seconds,
  reconnectWait        = 2.seconds,
  maxReconnects        = 60,
  pingInterval         = 2.minutes,
  requestCleanupInterval = 5.seconds,
  bufferSize           = 64 * 1024,   // bytes
  noEcho               = false,
  utf8Support          = false,
  inboxPrefix          = "_INBOX.",
  // Authentication (pick one):
  token                = None,
  username             = None,
  password             = None,
  credentialPath       = None,
  authHandler          = None,
  // Escape hatch for any Options.Builder field not covered above:
  optionsCustomizer    = identity
)
```

Convenience constructors:

```scala
NatsConfig.default        // localhost:4222
NatsConfig("nats://host:4222")
```

`Nats.default` is a `ZLayer` using `NatsConfig.default` — useful for local dev:

```scala
program.provide(Nats.default)
```

## Examples

See [`examples/`](examples/) for runnable apps. `QuickStartApp` requires only a plain NATS server; all others require JetStream (`docker run -p 4222:4222 nats -js`).

| File                                                                             | What it shows                                                    |
|----------------------------------------------------------------------------------|------------------------------------------------------------------|
| [`QuickStartApp`](examples/src/main/scala/QuickStartApp.scala)                   | Connect, publish, subscribe, receive 3 messages                  |
| [`TypedMessagingApp`](examples/src/main/scala/TypedMessagingApp.scala)           | Type-safe pub/sub with `NatsCodec` and `Envelope[A]`             |
| [`JetStreamApp`](examples/src/main/scala/JetStreamApp.scala)                     | JetStream publish, fetch, consume, ordered consumer              |
| [`KeyValueApp`](examples/src/main/scala/KeyValueApp.scala)                       | KV put/get/watch/history with typed codec                        |
| [`ObjectStoreApp`](examples/src/main/scala/ObjectStoreApp.scala)                 | Object store chunked upload, streaming, links, watch             |
| [`ServiceApp`](examples/src/main/scala/ServiceApp.scala)                         | Service framework: typed endpoints, discovery, stats             |
| [`RealisticApp`](examples/src/main/scala/RealisticApp.scala)                     | JetStream + KV end-to-end order processing                       |

```
sbt "zioNatsExamples/runMain QuickStartApp"
sbt "zioNatsExamples/runMain TypedMessagingApp"
sbt "zioNatsExamples/runMain JetStreamApp"
sbt "zioNatsExamples/runMain KeyValueApp"
sbt "zioNatsExamples/runMain ObjectStoreApp"
sbt "zioNatsExamples/runMain ServiceApp"
sbt "zioNatsExamples/runMain RealisticApp"
```
