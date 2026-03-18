# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation Policy

When making any code changes, keep documentation in sync:

- **ScalaDocs**: Update or add ScalaDocs on any modified public traits, classes, methods, or enums.
- **`CLAUDE.md`**: Update the Architecture, Key Files, or Project Layout sections if the structure or behaviour changes.
- **`README.md`** (if present): Reflect any public API additions, removals, or behavioural changes.

## Build & Dev Commands

sbt is managed via Coursier; invoke it as:
```
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat <command>
```

| Goal | Command |
|------|---------|
| Compile everything | `compile` |
| Run all tests | `zioNatsTest/test` |
| Compile tests only | `zioNatsTest/Test/compile` |
| Run a single test | `zioNatsTest/testOnly zio.nats.KeyValueSpec` |
| Format sources | `scalafmt` |
| Run examples | `zioNatsExamples/run` (requires a running NATS server with JetStream: `docker run -p 4222:4222 nats -js`) |

Tests use real NATS via testcontainers (Docker required). They start automatically; no manual NATS setup needed for `test`.

## Project Layout

Four sbt subprojects:

| Subproject | Purpose |
|-----------|---------|
| `zio-nats` | Core library — all public API |
| `zio-nats-testkit` | `NatsTestLayers` for integration tests |
| `zio-nats-test` | Integration test suite (24 tests) |
| `examples` | Runnable demos |

## Architecture

### Service Layer

All services are plain Scala traits backed by `*Live` implementations in the same file. Each service is obtained via a `ZLayer`:

```
NatsConfig ──► Nats.live ──► JetStream.live
                         ──► KeyValue.bucket(name)
                         ──► KeyValueManagement.live
                         ──► ObjectStore.bucket(name)
                         ──► ObjectStoreManagement.live
```

`Nats` holds the raw jnats `Connection`. All other services derive from it via `nats.underlying`. No jnats types are exposed in the public API — every method parameter and return type is a Scala type from this library.

Connection lifecycle events are exposed via `Nats.lifecycleEvents: ZStream[Nats, Nothing, NatsEvent]`. The event infrastructure (queue, hub, listeners) is set up internally in `Nats.make` before `connect()` is called.

### Typed Serialization

`NatsCodec[A]` is the serialization typeclass. It is resolved implicitly at compile time.

- Built-in instances: `NatsCodec[Chunk[Byte]]` (identity) and `NatsCodec[String]` (UTF-8).
- For domain types: derive from a zio-blocks `Format` + `Schema`:
  ```scala
  val builder = NatsCodec.fromFormat(JsonFormat)
  import builder.derived  // enables implicit NatsCodec[A] for any A with a Schema
  ```
- Multiple formats can coexist; per-type overrides are plain `implicit val`s.

#### Codec lifecycle guarantees

`NatsCodec.Builder.derived[A]` is a polymorphic `given` (equivalent to `implicit def`), so it
creates a new instance on every implicit resolution. To avoid redundant re-derivation, `Builder`
caches codec instances in a `ConcurrentHashMap` keyed on the `Schema[A]` instance. This means:

1. **Lazy first-use construction** — `NatsSerializer.makeFor[A](format)` is called the first time
   `NatsCodec[A]` is resolved for a given type `A`. If the format has no `Deriver` for `A`, an
   exception is thrown then, not buried inside a later `encode` call.
2. **Cached thereafter** — subsequent resolutions for the same `A` return the cached codec without
   re-deriving. The cache key is the `Schema[A]` instance, which is stable for the typical case of
   companion-object `given val` schemas.
3. **Safe encode** — once the codec is constructed, `NatsCodec[A].encode(value)` delegates to a
   pre-built `NatsSerializer.CompiledCodec[A]`. Any residual throw (e.g. OOM) is a library defect,
   not a schema error.

At every call site (`publish`, `put`, `create`, `update`, etc.) the `encode` call is wrapped in
`ZIO.attempt(...).mapError(e => NatsError.SerializationError(...))` so even defect-level throws
surface as typed `NatsError` rather than ZIO defects.

All publish/put/get/create/update methods are generic `[A: NatsCodec]`. Passing `Chunk[Byte]` or `String` selects the built-in codecs via type inference — no overloads.

### Opaque Types

`Subject` and `QueueGroup` are opaque type aliases for `String` (zero runtime overhead). Use `Subject("my.topic")` to construct; `subject.value` to unwrap. Defined in `NatsCoreTypes.scala`.

### Error Model

`NatsError` is a sealed ADT extending `NoStackTrace`. Sub-sealed traits group domain errors:
- `JetStreamError` → `JetStreamApiError`, `JetStreamPublishFailed`, `JetStreamConsumeFailed`
- `KeyValueError` → `KeyNotFound`
- `ObjectStoreError`

### Package Structure

The library uses sub-packages for each feature area. Everything is re-exported from `package object nats` so `import zio.nats.*` remains the only import end-users need.

```
zio.nats
├── jetstream/          JetStream service, management, consumer, models, and config
├── kv/                 Key-Value service, management, models, and config
├── objectstore/        Object Store service, management, models, and config
└── config/             NatsConfig (connection settings only)
```

Opaque types (`Subject`, `QueueGroup`) cannot be moved to sub-packages — re-exporting an opaque type as a plain alias strips its opacity — so they stay in `zio.nats`.

`private[nats]` visibility is preserved across sub-packages: Scala includes sub-packages in its visibility scope.

### Key Files

| File | Contents |
|------|----------|
| `zio-nats/src/main/scala/zio/nats/Nats.scala` | Core pub/sub service + `NatsLive`; `lifecycleEvents` stream |
| `zio-nats/src/main/scala/zio/nats/NatsCodec.scala` | Serialization typeclass + `Builder` (eager codec construction via `NatsSerializer.makeFor`) |
| `zio-nats/src/main/scala/zio/nats/serialization/NatsSerializer.scala` | `CompiledCodec[A]` sealed trait, `makeFor[A](format)` factory (eager, can throw), `BinaryCompiledCodec` / `TextCompiledCodec` impls, static one-shot `encode`/`decode` for backward compat |
| `zio-nats/src/main/scala/zio/nats/NatsError.scala` | Error hierarchy |
| `zio-nats/src/main/scala/zio/nats/NatsCoreTypes.scala` | `Subject` (opaque), `QueueGroup` (opaque), `Headers`, `PublishParams`, `StorageType`, `ConnectionStatus`, `NatsServerInfo` |
| `zio-nats/src/main/scala/zio/nats/NatsModels.scala` | `Envelope[+A]`, `ConnectionStats` |
| `zio-nats/src/main/scala/zio/nats/NatsEvent.scala` | `NatsEvent` enum |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStream.scala` | JetStream publish + `JetStreamLive` |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStreamManagement.scala` | JetStream stream/consumer admin |
| `zio-nats/src/main/scala/zio/nats/jetstream/Consumer.scala` | `Consumer`, `OrderedConsumer` traits + live impls |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStreamMessage.scala` | `JetStreamMessage` with ack operations |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStreamModels.scala` | `JsEnvelope`, `PublishAck`, `PublishOptions`, `FetchOptions`, `ConsumeOptions`, summaries, etc. |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStreamConfig.scala` | `StreamConfig`, `ConsumerConfig`, `OrderedConsumerConfig`, `MirrorConfig`, `SourceConfig`, `ExternalConfig` |
| `zio-nats/src/main/scala/zio/nats/kv/KeyValue.scala` | KV service + management |
| `zio-nats/src/main/scala/zio/nats/kv/KeyValueModels.scala` | `KeyValueEntry`, `KvEnvelope`, `KvEvent`, `KeyValueOperation`, watch options, bucket status |
| `zio-nats/src/main/scala/zio/nats/kv/KeyValueConfig.scala` | `KeyValueConfig`, `RepublishConfig` |
| `zio-nats/src/main/scala/zio/nats/objectstore/ObjectStore.scala` | ObjectStore service + management |
| `zio-nats/src/main/scala/zio/nats/objectstore/ObjectStoreModels.scala` | `ObjectMeta`, `ObjectData`, `ObjectSummary`, watch options, bucket status |
| `zio-nats/src/main/scala/zio/nats/objectstore/ObjectStoreConfig.scala` | `ObjectStoreConfig` |
| `zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala` | Connection config (host, port, TLS, reconnect) |
| `zio-nats/src/main/scala/zio/nats/package.scala` | Type aliases (`NatsIO[A]`), all sub-package re-exports |
| `zio-nats-testkit/src/main/scala/zio/nats/NatsTestLayers.scala` | Test layers (Docker NATS) |
