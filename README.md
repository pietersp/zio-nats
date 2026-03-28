# zio-nats

NATS is one of the fastest messaging systems available - lightweight, cloud-native, and built for scale. zio-nats brings the full NATS ecosystem into ZIO 2: subscriptions become `ZStream`s, services become `ZLayer`s, errors are typed, and not a single callback touches your code.

![ZIO 2](https://img.shields.io/badge/ZIO-2-red)
![Scala 3](https://img.shields.io/badge/scala-3-blue)
[![Development](https://img.shields.io/badge/Project%20Stage-Development-green.svg)](https://github.com/zio/zio/wiki/Project-Stages)
![Version](https://img.shields.io/badge/version-coming%20soon-orange)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.pietersp/zio-nats_3)](https://central.sonatype.com/artifact/io.github.pietersp/zio-nats_3)
![License](https://img.shields.io/badge/license-Apache--2.0-green)

---

## Why zio-nats

**Everything is a stream.** `Nats#subscribe`, `Consumer#consume`, `KeyValue#watch`, and `ObjectStore#watch` all return `ZStream`. Backpressure, interruption, and resource cleanup are handled automatically.

**Full NATS coverage.** Core pub/sub, JetStream persistent messaging, Key-Value store, Object Store, and the Service Framework - the complete NATS API in one library.

**Type-safe from publish to subscribe.** One `Schema` derivation covers every format - JSON, Avro, MsgPack. Prefer jsoniter-scala or play-json? Bring your own codec and it bridges automatically.

**Testing without mocks.** `NatsTestLayers.nats` starts a real NATS server in Docker. One line, no infrastructure to manage, no mocks to drift from the real protocol.

---

## Quick start

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "<version>"
```

Start a local NATS server:

```sh
docker run -p 4222:4222 nats
```

Subscribe and publish:

```scala
import zio.*
import zio.nats.*

object HelloNats extends ZIOAppDefault {
  def run =
    (for {
      nats  <- ZIO.service[Nats]
      fiber <- nats.subscribe[String](Subject("greetings"))
                 .take(1)
                 .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                 .runDrain
                 .fork
      _     <- ZIO.sleep(100.millis)
      _     <- nats.publish(Subject("greetings"), "Hello, NATS!")
      _     <- fiber.join
    } yield ()).provide(Nats.default)
}
```

`Nats.default` connects to `localhost:4222`. Every other service - JetStream, KV, Object Store - is a `ZLayer` derived from it.

---

## Highlights

### Typed domain messages

Define a schema once and publish or subscribe with any domain type across any wire format, with no per-call codec configuration:

```scala
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class OrderPlaced(orderId: String, total: Double)
object OrderPlaced { given Schema[OrderPlaced] = Schema.derived }

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived  // NatsCodec[OrderPlaced] is now in scope

nats.publish(Subject("orders.new"), OrderPlaced("ord-1", 59.99))
nats.subscribe[OrderPlaced](Subject("orders.>")).map(_.value).runDrain
```

Switch `JsonFormat` to `AvroFormat` or `MsgPackFormat` and the wire format changes - your domain code does not.

### JetStream - persistent streams with up to exactly-once delivery

Messages survive server restarts. Consumer positions are tracked server-side and resume exactly where they left off after a crash or reconnect:

```scala
// Publish with a delivery guarantee - blocks until the server confirms storage
val ack: PublishAck = js.publish(Subject("orders.new"), order)

// Consume durably - ack each message to advance the server-side cursor
consumer.consume[OrderPlaced]()
  .mapZIO(env => fulfill(env.value) *> env.message.ack)
  .runDrain
```

### Reactive Key-Value store

Every bucket change streams to your application in real time. Use it for live configuration, feature flags, or any shared state that needs to stay in sync across services:

```scala
kv.watchAll[FeatureConfig](KeyValueWatchOptions())
  .collect { case KvEvent.Put(env) => env.value }
  .tap(cfg => applyConfig(cfg))
  .runDrain
  .fork
```

### Object Store - large binary objects, no S3 required

Store and stream arbitrarily large objects across your NATS cluster. Chunking and reassembly are handled transparently - a 10-byte config file and a 2 GB model use the same API:

```scala
val os = ObjectStore.bucket("models")

// Store
os.put("gpt-small.bin", modelBytes)

// Stream a large object without buffering it in JVM heap
os.getStream("gpt-small.bin")
  .grouped(4096)
  .tap(chunk => ZIO.debug(s"${chunk.length} bytes"))
  .runDrain
```

### Service Framework - typed microservices over NATS

Define typed request-reply endpoints and let NATS handle routing, load balancing, and service discovery - no service mesh, no sidecar:

```scala
val prices = ServiceEndpoint("prices").in[String].out[Double]

nats.service(
  ServiceConfig("pricing", "1.0.0"),
  prices.handle(itemId => ZIO.succeed(pricingEngine.lookup(itemId)))
)

// On the client side - call any instance by name, NATS picks one
nats.request[String, Double](Subject("prices"), "item-42", 5.seconds)
```

Typed domain errors accumulate through chained `failsWith` calls, so endpoints can expose as many distinct error variants as they need:

```scala
case class NotFound(id: String)
case class ValidationError(message: String)

val lookup = ServiceEndpoint("lookup-user")
  .in[String]
  .out[String]
  .failsWith[NotFound]
  .failsWith[ValidationError]

val reply: ZIO[Nats, NatsError | NotFound | ValidationError, String] =
  ZIO.serviceWithZIO[Nats](_.requestService(lookup, "user-42", 5.seconds))
```

### Integration tests with a real server

```scala
object OrderSpec extends ZIOSpecDefault {
  def spec = suite("OrderSpec")(
    test("processes a placed order") {
      for {
        nats <- ZIO.service[Nats]
        // ... your test
      } yield assertTrue(true)
    }
  ).provideShared(NatsTestLayers.nats) @@ sequential @@ withLiveClock
}
```

`NatsTestLayers.nats` starts a NATS container via [testcontainers](https://testcontainers.com), waits for it to be ready, and provides a fully wired `Nats` service. `.provideShared` starts the container once for the whole suite - fast and cheap.

---

## Documentation

Full guides and API reference at **[pietersp.github.io/zio-nats](https://pietersp.github.io/zio-nats/)**.

[Quick start](https://pietersp.github.io/zio-nats/quickstart) · [Pub/Sub](https://pietersp.github.io/zio-nats/guides/pubsub) · [Serialization](https://pietersp.github.io/zio-nats/guides/serialization) · [JetStream](https://pietersp.github.io/zio-nats/guides/jetstream) · [Key-Value](https://pietersp.github.io/zio-nats/guides/key-value) · [Object Store](https://pietersp.github.io/zio-nats/guides/object-store) · [Service Framework](https://pietersp.github.io/zio-nats/guides/service) · [Testing](https://pietersp.github.io/zio-nats/guides/testing) · [Modules](https://pietersp.github.io/zio-nats/reference/modules)

Contributor setup notes live in `DEVELOPMENT.md`.

---

## License

Apache 2.0.
