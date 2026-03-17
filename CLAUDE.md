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

All publish/put/get/create/update methods are generic `[A: NatsCodec]`. Passing `Chunk[Byte]` or `String` selects the built-in codecs via type inference — no overloads.

### Opaque Types

`Subject` and `QueueGroup` are opaque type aliases for `String` (zero runtime overhead). Use `Subject("my.topic")` to construct; `subject.value` to unwrap. Defined in `NatsCoreTypes.scala`.

### Error Model

`NatsError` is a sealed ADT extending `NoStackTrace`. Sub-sealed traits group domain errors:
- `JetStreamError` → `JetStreamApiError`, `JetStreamPublishFailed`, `JetStreamConsumeFailed`
- `KeyValueError` → `KeyNotFound`
- `ObjectStoreError`

### Key Files

| File | Contents |
|------|----------|
| `zio-nats/src/main/scala/zio/nats/Nats.scala` | Core pub/sub service + `NatsLive`; `lifecycleEvents` stream |
| `zio-nats/src/main/scala/zio/nats/JetStream.scala` | JetStream publish + `JetStreamLive` |
| `zio-nats/src/main/scala/zio/nats/KeyValue.scala` | KV service + management |
| `zio-nats/src/main/scala/zio/nats/ObjectStore.scala` | ObjectStore service + management |
| `zio-nats/src/main/scala/zio/nats/NatsCodec.scala` | Serialization typeclass |
| `zio-nats/src/main/scala/zio/nats/NatsError.scala` | Error hierarchy |
| `zio-nats/src/main/scala/zio/nats/NatsCoreTypes.scala` | `Subject`, `QueueGroup`, `Headers`, `PublishParams`, enums |
| `zio-nats/src/main/scala/zio/nats/NatsModels.scala` | `PublishAck`, `JsPublishParams`, `KeyValueEntry`, etc. |
| `zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala` | Connection config |
| `zio-nats/src/main/scala/zio/nats/configuration/Configuration.scala` | Stream/Consumer/KV/ObjectStore config |
| `zio-nats/src/main/scala/zio/nats/package.scala` | Type aliases (`NatsIO[A]`), re-exports |
| `zio-nats-testkit/src/main/scala/zio/nats/NatsTestLayers.scala` | Test layers (Docker NATS) |
