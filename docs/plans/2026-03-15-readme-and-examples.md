# README and Examples Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Write a thorough open-source README.md and two runnable example apps (`QuickStartApp`, `RealisticApp`) for the `zio-nats` library.

**Architecture:** Single `README.md` at project root using progressive disclosure. A new `examples/` sbt sub-module holds two compilable `ZIOAppDefault` objects. `build.sbt` is extended to aggregate the new module.

**Tech Stack:** Scala 2.13 / 3, ZIO 2, sbt, jnats 2.25.2

---

### Task 1: Add `examples/` sbt sub-module to build.sbt

**Files:**
- Modify: `build.sbt`

**Step 1: Add the `zioNatsExamples` project definition and add it to `root.aggregate`**

At the end of `build.sbt`, add:

```scala
lazy val zioNatsExamples = (project in file("examples"))
  .dependsOn(zioNats)
  .settings(
    name           := "zio-nats-examples",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion
    )
  )
```

Also update the `root` project's `.aggregate(...)` call to include `zioNatsExamples`:

```scala
lazy val root = (project in file("."))
  .aggregate(zioNats, zioNatsTestkit, zioNatsTest, zioNatsExamples)
  ...
```

**Step 2: Verify sbt compiles the new module**

Run: `sbt compile`
Expected: `[success]` — the empty examples module compiles

**Step 3: Commit**

```
git add build.sbt
git commit -m "chore: add examples/ sbt sub-module"
```

---

### Task 2: Write QuickStartApp.scala

**Files:**
- Create: `examples/src/main/scala/QuickStartApp.scala`

**Step 1: Create the file**

```scala
import zio._
import zio.nats._
import zio.nats.config.NatsConfig

/** Minimal zio-nats quick-start.
  *
  * Requires a running NATS server: nats-server
  *
  * Run with: sbt "zioNatsExamples/run"
  */
object QuickStartApp extends ZIOAppDefault {

  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        nats <- Nats.make(NatsConfig.default)

        // Subscribe in the background; take the first 3 messages
        fiber <- nats.subscribe("greetings")
                   .take(3)
                   .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}"))
                   .runDrain
                   .fork

        // Give the subscription a moment to register
        _ <- ZIO.sleep(200.millis)

        // Publish 3 messages
        _ <- ZIO.foreach(1 to 3)(i =>
               nats.publish("greetings", s"Hello #$i".toNatsData)
             )

        // Wait for the subscriber fiber to finish
        _ <- fiber.join
      } yield ()
    }.mapError(e => new RuntimeException(e.toString))
}
```

**Step 2: Verify it compiles**

Run: `sbt "zioNatsExamples/compile"`
Expected: `[success]`

**Step 3: Commit**

```
git add examples/src/main/scala/QuickStartApp.scala
git commit -m "feat: add QuickStartApp example"
```

---

### Task 3: Write RealisticApp.scala

**Files:**
- Create: `examples/src/main/scala/RealisticApp.scala`

**Step 1: Create the file**

```scala
import zio._
import zio.nats._
import zio.nats.config.NatsConfig
import io.nats.client.api.{
  StreamConfiguration, StorageType,
  ConsumerConfiguration, AckPolicy,
  KeyValueConfiguration
}
import io.nats.client.{FetchConsumeOptions}

/** More realistic zio-nats example.
  *
  * Demonstrates:
  *   - JetStream stream creation and publishing
  *   - Consuming messages as a ZStream with explicit ack
  *   - Key-Value store for tracking state
  *   - Connection events via NatsConnectionEvents
  *
  * Requires JetStream-enabled NATS: nats-server -js
  *
  * Run with: sbt "zioNatsExamples/run"
  */
object RealisticApp extends ZIOAppDefault {

  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        // --- Connection events (must be wired before connect) ---
        (events, customizer) <- NatsConnectionEvents.make
        _ <- events
               .tap(e => Console.printLine(s"[event] $e"))
               .runDrain
               .fork

        // --- Connect ---
        nats <- Nats.make(
                  NatsConfig.default.copy(optionsCustomizer = customizer)
                )

        // --- JetStream management: create stream ---
        jsm <- ZIO.attempt(nats.underlying.jetStreamManagement())
        streamCfg = StreamConfiguration.builder()
                      .name("ORDERS")
                      .subjects("orders.>")
                      .storageType(StorageType.Memory)
                      .build()
        _ <- ZIO.attempt(jsm.addStream(streamCfg))

        // --- Create a durable pull consumer ---
        consumerCfg = ConsumerConfiguration.builder()
                        .durable("order-processor")
                        .ackPolicy(AckPolicy.Explicit)
                        .build()
        _ <- ZIO.attempt(jsm.addOrUpdateConsumer("ORDERS", consumerCfg))

        // --- KV bucket to track processed count ---
        kvm    <- ZIO.attempt(nats.underlying.keyValueManagement())
        kvCfg   = KeyValueConfiguration.builder().name("state").build()
        _      <- ZIO.attempt(kvm.create(kvCfg))
        kv     <- KeyValue.bucket("state").provide(ZLayer.succeed(nats))
        _      <- kv.put("processed", "0")

        // --- Publish 5 orders ---
        js <- ZIO.attempt(nats.underlying.jetStream())
        _ <- ZIO.foreach(1 to 5)(i =>
               ZIO.attempt(js.publish(s"orders.new", s"order-$i".getBytes))
             )
        _ <- Console.printLine("Published 5 orders")

        // --- Consume as ZStream, ack each one ---
        consumerCtx <- ZIO.attempt(
                         js.getConsumerContext("ORDERS", "order-processor")
                       )
        fetchOpts = FetchConsumeOptions.builder().maxMessages(5).expiresIn(5000).build()
        _ <- JetStreamConsumer
               .fetch(consumerCtx, fetchOpts)
               .mapZIO { msg =>
                 for {
                   _     <- Console.printLine(s"Processing: ${msg.dataAsString}")
                   _     <- msg.ack
                   entry <- kv.get("processed")
                   count  = entry.map(_.valueAsString().toInt).getOrElse(0)
                   _     <- kv.put("processed", (count + 1).toString)
                 } yield ()
               }
               .runDrain

        // --- Report final count ---
        finalEntry <- kv.get("processed")
        _          <- Console.printLine(
                        s"Done. Processed: ${finalEntry.map(_.valueAsString()).getOrElse("0")} orders"
                      )

      } yield ()
    }.mapError(e => new RuntimeException(e.toString))
}
```

**Step 2: Verify it compiles**

Run: `sbt "zioNatsExamples/compile"`
Expected: `[success]`

**Step 3: Commit**

```
git add examples/src/main/scala/RealisticApp.scala
git commit -m "feat: add RealisticApp example"
```

---

### Task 4: Write README.md

**Files:**
- Create: `README.md`

**Step 1: Write the full README**

Content outline with exact section headers and non-trivial snippet content follows. Write every section in one pass.

#### 1. Header

```markdown
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
- Cross-compiled for Scala 2.13 and Scala 3
```

#### 2. Installation

```markdown
## Installation

Add to `build.sbt`:

\`\`\`scala
// Core library
libraryDependencies += "dev.zio" %% "zio-nats" % "<version>"

// Testkit (for integration tests — brings in testcontainers)
libraryDependencies += "dev.zio" %% "zio-nats-testkit" % "<version>" % Test
\`\`\`
```

#### 3. Quick start

```markdown
## Quick start

\`\`\`scala
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
\`\`\`

> `toNatsData` is a string extension provided by `import zio.nats._`.  
> `Nats.make` returns a managed connection — it is closed automatically when the `Scope` ends.
```

#### 4. Core concepts

```markdown
## Core concepts

`Nats` is the root service. All other services are derived from it:

\`\`\`
NatsConfig
    └── Nats.live              ← core connection (pub/sub/request)
            ├── JetStream.live          ← JetStream publishing
            ├── JetStreamManagement.live← stream + consumer admin
            ├── KeyValueManagement.live ← KV bucket admin
            └── ObjectStoreManagement.live ← Object Store admin
\`\`\`

`KeyValue.bucket(name)` and `ObjectStore.bucket(name)` are effects that
return service instances bound to a specific bucket.

All services are obtained via `ZLayer` composition:

\`\`\`scala
val appLayer =
  NatsConfig.live >>>
  Nats.live.mapError(_.toException) >>>
  JetStream.live.mapError(_.toException)
\`\`\`
```

#### 5. Pub/Sub & Request-Reply

```markdown
## Pub/Sub & Request-Reply

### Publish

\`\`\`scala
// Plain bytes
nats.publish("events.user", bytes)

// UTF-8 string (via toNatsData extension)
nats.publish("events.user", "payload".toNatsData)

// With headers
nats.publish("events.user", bytes, Map("Content-Type" -> List("application/json")))
\`\`\`

### Subscribe

`subscribe` returns a `ZStream`. The jnats `Dispatcher` is created when the
stream is consumed and closed automatically when the stream is interrupted.

\`\`\`scala
nats.subscribe("events.>")
  .tap(msg => ZIO.debug(s"got ${msg.subject}: ${msg.dataAsString}"))
  .runDrain
\`\`\`

### Queue groups

Messages are load-balanced across all subscribers in the same queue group:

\`\`\`scala
nats.subscribe("work.queue", "workers")
\`\`\`

### Request-Reply

\`\`\`scala
val reply: IO[NatsError, NatsMessage] =
  nats.request("rpc.add", payload, timeout = 5.seconds)
\`\`\`
```

#### 6. JetStream

```markdown
## JetStream

### Publishing

\`\`\`scala
val layer = NatsConfig.live >>> Nats.live >>> JetStream.live

val publish =
  JetStream.publish("orders.new", order.toNatsData)
    .map(ack => println(s"seq=${ack.getSeqno}"))
    .provide(layer)
\`\`\`

### Management

\`\`\`scala
val layer = NatsConfig.live >>> Nats.live >>> JetStreamManagement.live

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
\`\`\`

### Consuming

Get a `JConsumerContext` from `JetStream`, then use `JetStreamConsumer`:

\`\`\`scala
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

// Single next message
JetStreamConsumer.next(consumerCtx, timeout = 5.seconds)
\`\`\`

#### Ack methods on `NatsMessage`

| Method | Effect |
|--------|--------|
| `msg.ack` | Acknowledge successful processing |
| `msg.ackSync(timeout)` | Ack and wait for server confirmation |
| `msg.nak` | Request redelivery immediately |
| `msg.nakWithDelay(d)` | Request redelivery after delay |
| `msg.term` | Terminate — do not redeliver |
| `msg.inProgress` | Extend ack deadline (work-in-progress signal) |
```

#### 7. Key-Value

```markdown
## Key-Value store

### Setup

\`\`\`scala
val kvm   = KeyValueManagement.live  // requires Nats in environment
val setup = KeyValueManagement.create(
              KeyValueConfiguration.builder().name("config").build()
            )
\`\`\`

### Operations

\`\`\`scala
val kv: KeyValue = // from KeyValue.bucket("config")

// Put
kv.put("feature.flag", "true")
kv.put("payload", bytes)

// Get
kv.get("feature.flag")          // IO[NatsError, Option[KeyValueEntry]]

// Compare-and-swap
kv.create("lock", bytes)                   // create-only
kv.update("lock", newBytes, revision = 3)  // update if revision matches

// Delete / purge
kv.delete("stale-key")  // soft delete (history preserved)
kv.purge("old-key")     // remove all history

// Watch for changes
kv.watch("feature.>")   // ZStream[Any, NatsError, KeyValueEntry]
kv.watchAll             // watch entire bucket
\`\`\`
```

#### 8. Object Store

```markdown
## Object Store

\`\`\`scala
// Management
val osm   = ObjectStoreManagement.live  // requires Nats
val setup = ObjectStoreManagement.create(
              ObjectStoreConfiguration.builder().name("assets").build()
            )

// Operations
val os: ObjectStore = // from ObjectStore.bucket("assets")

os.put("logo.png", imageBytes)           // IO[NatsError, ObjectInfo]
os.get("logo.png")                       // IO[NatsError, Chunk[Byte]]
os.getInfo("logo.png")                   // metadata only
os.delete("old-asset")                   // soft delete
os.list                                  // IO[NatsError, List[ObjectInfo]]
os.watch                                 // ZStream[Any, NatsError, ObjectInfo]
\`\`\`
```

#### 9. Connection Events

```markdown
## Connection Events

Wire up before connecting — the customizer must be applied to `NatsConfig.optionsCustomizer`:

\`\`\`scala
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
\`\`\`

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
```

#### 10. Error handling

```markdown
## Error handling

All operations return `IO[NatsError, A]`. `NatsError` is a sealed trait:

\`\`\`scala
import zio.nats.NatsError._

nats.publish("subject", bytes).catchAll {
  case ConnectionClosed(msg)           => ZIO.logError(s"Connection closed: $msg")
  case Timeout(msg)                    => ZIO.logWarning(s"Timed out: $msg")
  case JetStreamApiError(msg, code, apiCode, _) =>
    ZIO.logError(s"JetStream API error $code/$apiCode: $msg")
  case KeyNotFound(key)                => ZIO.logInfo(s"Key $key not found")
  case other                           => ZIO.logError(other.message)
}
\`\`\`

Sub-sealed traits for domain grouping:
- `NatsError.JetStreamError` — all JetStream errors
- `NatsError.KeyValueError` — includes `KeyNotFound`
- `NatsError.ObjectStoreError`
```

#### 11. Testing

```markdown
## Testing

Add the testkit dependency:

\`\`\`scala
libraryDependencies += "dev.zio" %% "zio-nats-testkit" % "<version>" % Test
\`\`\`

`NatsTestLayers.nats` starts a NATS container (via testcontainers) and provides
a `Nats` service wired to it. Use `.provideShared` to start the container once
per suite:

\`\`\`scala
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
\`\`\`

> **Podman / WSL users:** The testkit already includes a 500 ms settle delay after
> container start. Set `DOCKER_HOST=unix:///tmp/podman/podman-machine-default-api.sock`
> and `TESTCONTAINERS_RYUK_DISABLED=true` in your test environment (the provided
> `build.sbt` template handles this automatically).

For JetStream tests, use `NatsTestLayers.nats` — the container is started with
`--js` so all JetStream, KV, and Object Store APIs are available.
```

#### 12. NatsConfig reference

```markdown
## NatsConfig reference

\`\`\`scala
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
  // Escape hatch for any Options.Builder field not above:
  optionsCustomizer    = identity
)
\`\`\`

Convenience constructors:

\`\`\`scala
NatsConfig.default          // localhost:4222
NatsConfig("nats://host:4222")
\`\`\`

`Nats.default` is a `ZLayer` using `NatsConfig.default` — useful for local dev.
```

#### 13. Examples

```markdown
## Examples

See [`examples/`](examples/) for two runnable apps (require a local NATS server):

| File | What it shows |
|------|---------------|
| [`QuickStartApp`](examples/src/main/scala/QuickStartApp.scala) | Connect, publish, subscribe, receive 3 messages |
| [`RealisticApp`](examples/src/main/scala/RealisticApp.scala) | JetStream + KV + connection events, graceful shutdown |

Run with: `sbt "zioNatsExamples/run"`
```

**Step 2: Verify build still compiles**

Run: `sbt compile`
Expected: `[success]`

**Step 3: Commit**

```
git add README.md
git commit -m "docs: add thorough README with all API sections"
```

---

### Task 5: Commit design doc

**Files:**
- `docs/plans/2026-03-15-readme-and-examples-design.md` (already written)

**Step 1: Commit it**

```
git add docs/plans/2026-03-15-readme-and-examples-design.md
git commit -m "docs: add README + examples design doc"
```

---

### Task 6: Final verification

**Step 1: Ensure all modules compile**

Run: `sbt +compile`
Expected: `[success]` for both Scala 2.13 and Scala 3

**Step 2: Verify no `nul` file was accidentally staged**

Run: `git status`
Expected: working tree clean

**Step 3: Review git log**

Run: `git log --oneline -6`
Expected: the 3 new commits (chore: examples module, feat: examples, docs: readme)
