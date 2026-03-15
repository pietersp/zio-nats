# zio-nats

A ZIO 2 wrapper for the [jnats](https://github.com/nats-io/nats.java) NATS client.

![Scala 2.13](https://img.shields.io/badge/scala-2.13-blue)
![Scala 3](https://img.shields.io/badge/scala-3-blue)
![ZIO 2](https://img.shields.io/badge/ZIO-2-red)
![License](https://img.shields.io/badge/license-Apache--2.0-green)

- Idiomatic ZIO 2 services with `ZLayer` for every subsystem
- Full API coverage: core pub/sub, JetStream, Key-Value, Object Store
- `ZStream`-based subscriptions and consumers — no callbacks in user code
- Typed error model (`NatsError` sealed ADT)
- Type-safe serialization with [zio-blocks Schema](https://zio.dev/zio-blocks)
- Cross-compiled for Scala 2.13 and Scala 3

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
  val run =
    ZIO.scoped {
      for {
        nats  <- Nats.make(NatsConfig.default)
        fiber <- nats.subscribe("greetings")
                   .take(3)
                   .tap(msg => Console.printLine(msg.dataAsString))
                   .runDrain
                   .fork
        _     <- ZIO.sleep(100.millis)
        _     <- ZIO.foreach(1 to 3)(i =>
                   nats.publish("greetings", s"hello $i".toNatsData)
                 )
        _     <- fiber.join
      } yield ()
    }
}
```

> `toNatsData` is a string extension provided by `import zio.nats._`.  
> `Nats.make` returns a managed connection — it is closed automatically when the `Scope` ends.

## Core concepts

`Nats` is the root service. All other services are derived from it:

```
NatsConfig
    └── Nats.live              ← core connection (pub/sub/request-reply)
            ├── JetStream.live              ← JetStream publishing
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
// Plain bytes
nats.publish("events.user", bytes)

// UTF-8 string (via toNatsData extension)
nats.publish("events.user", "payload".toNatsData)

// With headers
nats.publish("events.user", bytes, Map("Content-Type" -> List("application/json")))
```

### Subscribe

`subscribe` returns a `ZStream`. The underlying jnats `Dispatcher` is created when the stream is consumed and closed automatically when the stream is interrupted.

```scala
nats.subscribe("events.>")
  .tap(msg => ZIO.debug(s"${msg.subject}: ${msg.dataAsString}"))
  .runDrain
```

### Queue groups

Messages are load-balanced across all subscribers in the same queue group:

```scala
nats.subscribe("work.queue", "workers")
```

### Request-Reply

```scala
val reply: IO[NatsError, NatsMessage] =
  nats.request("rpc.add", payload, timeout = 5.seconds)
```

## Type-Safe Serialization (zio-blocks)

zio-nats supports type-safe publish/subscribe using [zio-blocks Schema](https://zio.dev/zio-blocks). Provide an implicit `Schema[T]` and the library handles serialization automatically.

### Setup

Add zio-blocks-schema dependency:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema" % "0.0.29"
```

Define schemas for your types:

```scala
import zio.blocks.schema.Schema

case class Person(name: String, age: Int)
object Person {
  implicit val schema: Schema[Person] = Schema.derived
}

case class Order(id: String, amount: Double)
object Order {
  implicit val schema: Schema[Order] = Schema.derived
}
```

### Type-Safe Publish

```scala
// Publish typed data - automatically serialized to JSON
nats.publish(Subject("users"), Person("Alice", 30))

// With headers
nats.publish(Subject("orders"), Order("ord-123", 99.99), 
  headers = Map("Content-Type" -> List("application/json")))
```

### Type-Safe Subscribe

```scala
// Subscribe and deserialize to typed data
nats.subscribeAs[Person](Subject("users")).runForeach { person =>
  ZIO.debug(s"Got: ${person.name}")
}

// With queue group
nats.subscribeAs[Order](Subject("orders"), Subject("processors")).runDrain
```

### Subject Type

Use `Subject` for type-safe subjects:

```scala
import zio.nats.subject.Subject

val users = Subject("users")
nats.publish(users, Person("Bob", 25))
```

### JetStream Type-Safe Publish

```scala
val js: ZLayer[JetStream, NatsError, JetStream] = Nats.live >>> JetStream.live

JetStream.publish(Subject("events"), Event("click", timestamp))
  .provide(js)
```

### Configuration

The serialization format is configurable via `NatsConfig`:

```scala
NatsConfig(
  servers = List("nats://localhost:4222"),
  format = SerializationFormat.json  // default
)
```

## JetStream

### Publishing

```scala
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >>> JetStream.live

val publish =
  JetStream.publish("orders.new", order.toNatsData)
    .map(ack => println(s"seq=${ack.getSeqno}"))
    .provide(layer)
```

### Management

Create streams and consumers using `JetStreamManagement`:

```scala
val layer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStreamManagement.live

val setup = for {
  _ <- JetStreamManagement.addStream(
         StreamConfiguration.builder()
           .name("ORDERS")
           .subjects("orders.>")
           .storageType(StorageType.Memory)
           .build()
       )
  _ <- JetStreamManagement.addOrUpdateConsumer(
         "ORDERS",
         ConsumerConfiguration.builder()
           .durable("processor")
           .ackPolicy(AckPolicy.Explicit)
           .build()
       )
} yield ()
```

### Consuming

Get a `JConsumerContext` from `JetStream.consumerContext`, then use `JetStreamConsumer`:

```scala
// Indefinite push-style consume
JetStreamConsumer.consume(consumerCtx)
  .mapZIO(msg => process(msg) *> msg.ack)
  .runDrain

// Bounded fetch (completes after N messages or timeout)
JetStreamConsumer.fetch(consumerCtx, FetchConsumeOptions.builder().maxMessages(10).build())
  .mapZIO(msg => process(msg) *> msg.ack)
  .runDrain

// Pull-based iterate (retries on empty polls)
JetStreamConsumer.iterate(consumerCtx)
  .mapZIO(msg => process(msg) *> msg.ack)
  .runDrain

// Single next message (returns None if no message within timeout)
JetStreamConsumer.next(consumerCtx, timeout = 5.seconds)
```

#### Ack methods on `NatsMessage`

| Method | Effect |
|--------|--------|
| `msg.ack` | Acknowledge successful processing |
| `msg.ackSync(timeout)` | Ack and wait for server confirmation |
| `msg.nak` | Request redelivery immediately |
| `msg.nakWithDelay(d)` | Request redelivery after delay |
| `msg.term` | Terminate — do not redeliver |
| `msg.inProgress` | Extend ack deadline (work-in-progress signal) |

## Key-Value store

### Setup

```scala
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> KeyValueManagement.live

val createBucket =
  KeyValueManagement.create(
    KeyValueConfiguration.builder().name("config").build()
  ).provide(layer)
```

### Operations

```scala
// Obtain a bucket handle (requires Nats in the environment)
val kv: ZIO[Nats, NatsError, KeyValue] = KeyValue.bucket("config")

// Put
kv.put("feature.flag", "true")
kv.put("payload", bytes)

// Get
kv.get("feature.flag")          // IO[NatsError, Option[KeyValueEntry]]

// Compare-and-swap
kv.create("lock", bytes)                   // create-only (fails if key exists)
kv.update("lock", newBytes, expectedRevision = 3)  // update only if revision matches

// Delete / purge
kv.delete("stale-key")  // soft delete — history preserved
kv.purge("old-key")     // remove all history for the key

// Enumerate
kv.keys                 // IO[NatsError, List[String]]
kv.history("key")       // IO[NatsError, List[KeyValueEntry]]

// Watch for changes
kv.watch("feature.>")   // ZStream[Any, NatsError, KeyValueEntry]
kv.watchAll             // watch entire bucket
```

## Object Store

```scala
// Management
val layer = ZLayer.succeed(NatsConfig.default) >>> Nats.live >+> ObjectStoreManagement.live

val createBucket =
  ObjectStoreManagement.create(
    ObjectStoreConfiguration.builder().name("assets").build()
  ).provide(layer)

// Obtain a bucket handle (requires Nats in the environment)
val os: ZIO[Nats, NatsError, ObjectStore] = ObjectStore.bucket("assets")

os.put("logo.png", imageBytes)   // IO[NatsError, ObjectInfo]
os.get("logo.png")               // IO[NatsError, Chunk[Byte]]
os.getInfo("logo.png")           // metadata only
os.delete("old-asset")           // soft delete
os.list                          // IO[NatsError, List[ObjectInfo]]
os.watch                         // ZStream[Any, NatsError, ObjectInfo]
```

## Connection Events

Wire up `NatsConnectionEvents` before connecting — the customizer must be applied to `NatsConfig.optionsCustomizer`:

```scala
ZIO.scoped {
  for {
    (events, customizer) <- NatsConnectionEvents.make
    _    <- events
              .collect { case NatsEvent.Disconnected(url) => url }
              .tap(url => ZIO.logWarning(s"Disconnected from $url"))
              .runDrain
              .fork
    nats <- Nats.make(NatsConfig.default.copy(optionsCustomizer = customizer))
    // ...
  } yield ()
}
```

Event ADT:

| Event | When |
|-------|------|
| `Connected(url)` | Initial connection established |
| `Disconnected(url)` | Connection lost |
| `Reconnected(url)` | Reconnection successful |
| `ServersDiscovered(url)` | New cluster route discovered |
| `Closed` | Connection permanently closed |
| `LameDuckMode` | Server entering lame-duck shutdown |
| `Error(message)` | Non-fatal error string from server |
| `ExceptionOccurred(ex)` | Exception from the client |

## Error handling

All operations return `IO[NatsError, A]`. `NatsError` is a sealed trait with exhaustive pattern matching:

```scala
import zio.nats.NatsError._

nats.publish("subject", bytes).catchAll {
  case ConnectionClosed(msg)                          => ZIO.logError(s"Connection closed: $msg")
  case Timeout(msg)                                   => ZIO.logWarning(s"Timed out: $msg")
  case JetStreamApiError(msg, code, apiCode, _)       => ZIO.logError(s"JetStream API $code/$apiCode: $msg")
  case KeyNotFound(key)                               => ZIO.logInfo(s"Key $key not found")
  case other                                          => ZIO.logError(other.message)
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
        nats  <- ZIO.service[Nats]
        fiber <- nats.subscribe("t").take(1).runCollect.fork
        _     <- ZIO.sleep(200.millis)
        _     <- nats.publish("t", "hi".toNatsData)
        msgs  <- fiber.join
      } yield assertTrue(msgs.head.dataAsString == "hi")
    }
  ).provideShared(NatsTestLayers.nats) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
```

> **Podman / WSL users:** Set `DOCKER_HOST=unix:///tmp/podman/podman-machine-default-api.sock`
> and `TESTCONTAINERS_RYUK_DISABLED=true` in your test environment.

For JetStream tests use the same `NatsTestLayers.nats` — the container is started with `--js` so all JetStream, KV, and Object Store APIs are available.

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
  // Serialization format (default: JSON)
  format               = SerializationFormat.json,
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

See [`examples/`](examples/) for two runnable apps (require a local NATS server):

| File | What it shows |
|------|---------------|
| [`QuickStartApp`](examples/src/main/scala/QuickStartApp.scala) | Connect, publish, subscribe, receive 3 messages |
| [`RealisticApp`](examples/src/main/scala/RealisticApp.scala) | JetStream + KV + connection events, graceful shutdown |

Run with: `sbt "zioNatsExamples/run"`
