# zio-nats

A ZIO 2 wrapper for the [jnats](https://github.com/nats-io/nats.java) NATS client.

![Scala 3](https://img.shields.io/badge/scala-3-blue)
![ZIO 2](https://img.shields.io/badge/ZIO-2-red)
![License](https://img.shields.io/badge/license-Apache--2.0-green)

- Idiomatic ZIO 2 services with `ZLayer` for every subsystem
- Full API coverage: core pub/sub, JetStream, Key-Value, Object Store
- `ZStream`-based subscriptions and consumers — no callbacks in user code
- Typed error model (`NatsError` sealed ADT)
- Type-safe serialization with [zio-blocks Schema](https://zio.dev/zio-blocks)
- Zero raw jnats types in user code — `import zio.nats._` is all you need
- Scala 3

## Installation

Add to `build.sbt`:

```scala
// Core library
libraryDependencies += "dev.zio" %% "zio-nats" % "<version>"

// Testkit (for integration tests — brings in testcontainers)
libraryDependencies += "dev.zio" %% "zio-nats-testkit" % "<version>" % Test
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
      fiber <- Nats.subscribeRaw(Subject("greetings"))
                 .take(3)
                 .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}").orDie)
                 .runDrain
                 .fork
      _     <- ZIO.sleep(200.millis)
      _     <- ZIO.foreachDiscard(1 to 3) { i =>
                 Nats.publish(Subject("greetings"), s"Hello #$i")
               }
      _     <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
```

> `Subject` and `QueueGroup` are opaque types from `import zio.nats._` — no additional import needed.
> `Nats.subscribeRaw` returns raw `NatsMessage`s without decoding; `Nats.subscribe[A]` decodes each message and wraps it in `Envelope[A]`.

## Core concepts

`Nats` is the root service. All other services are derived from it:

```
NatsConfig
    └── Nats.live              ← core connection (pub/sub/request-reply)
            ├── JetStream.live              ← JetStream publishing + consumer access
            ├── JetStreamManagement.live    ← stream + consumer admin
            ├── KeyValueManagement.live     ← KV bucket admin
            └── ObjectStoreManagement.live  ← Object Store admin
```

`KeyValue.bucket(name)` and `ObjectStore.bucket(name)` are effects that return service instances bound to a specific bucket.

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
// UTF-8 string (built-in NatsCodec[String])
Nats.publish(Subject("events.user"), "payload")

// Raw bytes (built-in NatsCodec[Chunk[Byte]])
Nats.publish(Subject("events.user"), bytes)

// With headers
Nats.publish(
  Subject("events.user"),
  bytes,
  PublishParams(headers = Headers("Content-Type" -> "application/json"))
)

// With reply-to
Nats.publish(
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

`subscribe[A]` and `subscribeRaw` return a `ZStream`. The underlying jnats `Dispatcher` is created when the stream is consumed and closed automatically when the stream is interrupted.

```scala
// Raw NatsMessage — use subscribeRaw when you don't need typed decoding
Nats.subscribeRaw(Subject("events.>"))
  .tap(msg => ZIO.debug(s"${msg.subject}: ${msg.dataAsString}"))
  .runDrain
```

### Queue groups

Messages are load-balanced across all subscribers in the same queue group:

```scala
// Typed — decoded payload in Envelope
Nats.subscribe[WorkItem](Subject("work.queue"), QueueGroup("workers"))
  .map(_.value)
  .tap(item => process(item))
  .runDrain

// Raw bytes
Nats.subscribe[Chunk[Byte]](Subject("work.queue"), QueueGroup("workers"))
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

Add the zio-blocks-schema dependency (JSON is the default):

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema" % "0.0.29"
```

Define schemas for your types and install a codec builder:

```scala
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class Person(name: String, age: Int)
object Person {
  given schema: Schema[Person] = Schema.derived
}

// Install a default NatsCodec for all Schema-annotated types:
val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived  // NatsCodec[Person] is now in scope
```

### Type-Safe Publish

```scala
// Publish typed data — automatically serialized with the in-scope NatsCodec
Nats.publish(Subject("users"), Person("Alice", 30))
```

### Type-Safe Subscribe

`subscribe[A]` returns a `ZStream` of `Envelope[A]`. Each `Envelope` holds the decoded value and the raw `NatsMessage` (headers, subject, reply-to).

```scala
// Access decoded value via .value
Nats.subscribe[Person](Subject("users"))
  .map(_.value)
  .tap(person => ZIO.debug(s"Got: ${person.name}"))
  .runDrain

// Or use the .payload extension to strip the Envelope from the whole stream
Nats.subscribe[Person](Subject("users")).payload
  .tap(person => ZIO.debug(s"Got: ${person.name}"))
  .runDrain

// Keep the full Envelope for header access
Nats.subscribe[Person](Subject("users"))
  .tap(env => ZIO.debug(s"Headers: ${env.message.headers}, Person: ${env.value}"))
  .runDrain
```

Queue-group subscriptions also support typed decoding:

```scala
Nats.subscribe[Order](Subject("orders"), QueueGroup("processors")).payload
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

Add the corresponding zio-blocks artifact for the format you need:

```scala
// build.sbt — pick one (or more)
libraryDependencies += "dev.zio" %% "zio-blocks-schema"          % "0.0.29" // JSON
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"     % "0.0.29"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"     % "0.0.29"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack"  % "0.0.29"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"   % "0.0.29"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"     % "0.0.29"
```

```scala
import zio.blocks.schema.json.JsonFormat
import zio.blocks.schema.avro.AvroFormat
import zio.blocks.schema.toon.ToonFormat

val jsonCodecs  = NatsCodec.fromFormat(JsonFormat)
val avroCodecs  = NatsCodec.fromFormat(AvroFormat)
```

## JetStream

Requires JetStream-enabled NATS: `nats-server -js` or `docker run -p 4222:4222 nats -js`.

### Publishing

```scala
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >>> JetStream.live

val publish =
  for {
    js  <- ZIO.service[JetStream]
    ack <- js.publish(Subject("orders.new"), order.toNatsData)
    _   <- Console.printLine(s"seq=${ack.seqno}")
  } yield ()
```

Publish with duplicate detection via message ID, using `JsPublishParams`:

```scala
val ack = js.publish(
  Subject("orders.new"),
  order.toNatsData,
  JsPublishParams(options = Some(PublishOptions(messageId = Some("order-42"))))
)
```

Publish with headers:

```scala
val ack = js.publish(
  Subject("orders.new"),
  order.toNatsData,
  JsPublishParams(headers = Headers("X-Source" -> "checkout"))
)
```

Publish asynchronously (fire-and-forget, collect acks later):

```scala
// Returns IO[NatsError, Task[PublishAck]] — the inner Task resolves when the server acks
val futureAck: IO[NatsError, Task[PublishAck]] =
  js.publishAsync(Subject("orders.new"), order.toNatsData)
```

### Management

Create streams and consumers using `JetStreamManagement`. All config types are plain Scala case classes — no builders required:

```scala
val layer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStreamManagement.live

val setup = for {
  _ <- JetStreamManagement.addStream(
         StreamConfig(
           name        = "ORDERS",
           subjects    = List("orders.>"),
           storageType = StorageType.Memory
         )
       )
  _ <- JetStreamManagement.addOrUpdateConsumer(
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

  // Bounded fetch (completes after N messages or timeout)
  _ <- consumer.fetch(FetchOptions(maxMessages = 10, expiresIn = 5.seconds))
         .mapZIO(msg => process(msg) *> msg.ack)
         .runDrain

  // Indefinite push-style consume
  _ <- consumer.consume()
         .mapZIO(msg => process(msg) *> msg.ack)
         .runDrain

  // Pull-based iterate (retries on empty polls)
  _ <- consumer.iterate()
         .mapZIO(msg => process(msg) *> msg.ack)
         .runDrain

  // Single next message (returns None if no message within timeout)
  msg <- consumer.next(5.seconds)
} yield ()
```

### Consuming — OrderedConsumer

An ordered consumer guarantees strict in-order delivery and automatically re-creates itself on reconnect or sequence gaps. Unlike a durable consumer it requires no server-side state.

```scala
for {
  js      <- ZIO.service[JetStream]
  ordered <- js.orderedConsumer("EVENTS", OrderedConsumerConfig(
               filterSubjects = List("events.>"),
               deliverPolicy  = Some(DeliverPolicy.All)
             ))
  _ <- ordered.consume()
         .tap(msg => ZIO.debug(msg.dataAsString))
         .runDrain
} yield ()
```

`OrderedConsumer` exposes the same `fetch` / `consume` / `iterate` / `next` methods as `Consumer`.

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

val createBucket =
  KeyValueManagement.create(
    KeyValueConfig(name = "config", storageType = StorageType.Memory)
  ).provide(layer)
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
kv.keys                         // IO[NatsError, List[String]]
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

val createBucket =
  ObjectStoreManagement.create(
    ObjectStoreConfig(name = "assets", storageType = StorageType.Memory)
  ).provide(layer)

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

Wire up `NatsConnectionEvents` before connecting — the customizer must be applied to `NatsConfig.optionsCustomizer`:

```scala
ZIO.scoped {
  NatsConnectionEvents.make.flatMap { case (events, customizer) =>
    val logEvents = events
      .collect { case NatsEvent.Disconnected(url) => url }
      .tap(url => ZIO.logWarning(s"Disconnected from $url"))
      .runDrain
      .fork

    val natsLayer =
      ZLayer.succeed(NatsConfig.default.copy(optionsCustomizer = customizer)) >>>
      Nats.live

    logEvents *> program.provide(natsLayer)
  }
}
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
libraryDependencies += "dev.zio" %% "zio-nats-testkit" % "<version>" % Test
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
        fiber <- Nats.subscribeRaw(Subject("t")).take(1).runCollect.fork
        _     <- ZIO.sleep(200.millis)
        _     <- Nats.publish(Subject("t"), "hi")
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

See [`examples/`](examples/) for runnable apps. All require a running NATS server; `RealisticApp` additionally requires JetStream (`docker run -p 4222:4222 nats -js`).

| File                                                                         | What it shows                                                   |
|------------------------------------------------------------------------------|-----------------------------------------------------------------|
| [`QuickStartApp`](examples/src/main/scala/QuickStartApp.scala)               | Connect, publish, subscribe raw, receive 3 messages             |
| [`RealisticApp`](examples/src/main/scala/RealisticApp.scala)                 | JetStream + KV + connection events, graceful shutdown           |
| [`TypedMessagingApp`](examples/src/main/scala/TypedMessagingApp.scala)       | Type-safe publish/subscribe with `NatsCodec` and `Envelope[A]` |

```
sbt "zioNatsExamples/runMain QuickStartApp"
sbt "zioNatsExamples/runMain RealisticApp"
sbt "zioNatsExamples/runMain TypedMessagingApp"
```
