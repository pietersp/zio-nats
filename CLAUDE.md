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

## Testing Guidelines

**Test correctness over speed.** A fast test that doesn't verify the right behavior is worse than no test at all — it gives false confidence.

Key pitfalls to avoid:
- **Test natural completion, not interrupt behavior** — `take(n)` causes the stream to complete normally; the scope exits and drain is called regardless of interrupt. This doesn't verify that interrupt triggers drain.
- **Don't assume timing** — `ZIO.sleep` can be unreliable; use `Promise`, `Latch`, or `Ref` for synchronization.
- **Verify the fiber actually did something before asserting** — check that the fiber was actually running and did work, not just that the test didn't error.

When adding interrupt/cancellation tests:
- Use `ZIO.scoped { ... }.fork` to create a fiber with its own scope
- Have the subscription run indefinitely (no `take`) so it keeps working until interrupted
- Publish messages while the subscriber is active
- Interrupt the fiber and verify work was done before interrupt

## Project Layout

Seven sbt subprojects:

| Subproject | Purpose |
|-----------|---------|
| `zio-nats-core` | Core library — all public API; no zio-blocks dependency |
| `zio-nats-zio-blocks` | Optional zio-blocks integration (`NatsCodec.fromFormat`, `Builder`) |
| `zio-nats-jsoniter` | Optional jsoniter-scala integration (`NatsCodecJsoniter.fromJsoniter`) |
| `zio-nats-play-json` | Optional play-json integration (`NatsCodecPlayJson.fromPlayJson`) |
| `zio-nats` | Batteries-included wrapper — empty JAR, depends on `zio-nats-core` + `zio-nats-zio-blocks` |
| `zio-nats-testkit` | `NatsTestLayers` for integration tests |
| `zio-nats-test` | Integration test suite |
| `examples` | Runnable demos |

## Architecture

### Service Layer

All services are plain Scala traits backed by `*Live` implementations in the same file. Each service is obtained via a `ZLayer`:

```
NatsConfig ──► Nats.live ──► JetStream.live
                         ──► KeyValue.bucket(name)   // ZIO variant
                         ──► KeyValue.live(name)     // ZLayer variant
                         ──► KeyValueManagement.live
                         ──► ObjectStore.bucket(name)
                         ──► ObjectStore.live(name)  // ZLayer variant
                         ──► ObjectStoreManagement.live
```

`Nats` holds the raw jnats `Connection`. All other services derive from it via `nats.underlying`. No jnats types are exposed in the public API — every method parameter and return type is a Scala type from this library.

`Nats.requestService` is the typed client-side entry point for service endpoints: it accepts a `ServiceEndpoint[In, Err, Out]` as the complete contract and returns `IO[NatsError | Err, Out]`. Domain errors go directly into the ZIO error channel — call sites use Scala 3 union types which widen automatically across multiple calls with no manual merging. Domain errors (`Err`) are encoded into the reply body by the server (using `NatsCodec[Err]`) and decoded by the client. The `Nats-Service-Error` header is still set for NATS Micro compatibility. An empty body with the error header means an infrastructure error (`NatsError.ServiceCallFailed`); a non-empty body means a typed domain error (`Err`). `ServiceErrorMapper[E]` has a universal fallback given (`e.toString`, code 500) so `withError[E]` requires only a `NatsCodec[E]` in scope.

`Nats.request` is the untyped fallback: it detects `Nats-Service-Error` / `Nats-Service-Error-Code` headers and fails with `NatsError.ServiceCallFailed(message, code)` regardless of body content. Use it when the endpoint descriptor is not available or for infallible endpoints (via `endpoint.effectiveSubject`).

**Union error types** are supported via multi-type `failsWith` overloads. The server sets a `Nats-Service-Error-Type` header to the FQDN of the concrete runtime error class; the client dispatches decoding to the correct member codec using that tag. The dispatch table (`tagRoutes`) is built at compile time from `ErrorCodecPart` instances (one per member) and looked up in O(1) at runtime.

Connection lifecycle events are exposed via `Nats.lifecycleEvents: ZStream[Nats, Nothing, NatsEvent]`. The event infrastructure (queue, hub, listeners) is set up internally in `Nats.make` before `connect()` is called.

### Typed Serialization

`NatsCodec[A]` is the serialization typeclass. It is resolved implicitly at compile time. The core
module (`zio-nats-core`) has **no zio-blocks dependency** — it only provides built-in codecs for
`Chunk[Byte]` and `String`.

- Built-in instances (core): `NatsCodec[Chunk[Byte]]` (identity) and `NatsCodec[String]` (UTF-8).
- For domain types with zio-blocks schemas, add `zio-nats-zio-blocks` and use:
  ```scala
  val builder = NatsCodec.fromFormat(JsonFormat)
  import builder.derived  // enables implicit NatsCodec[A] for any A with a Schema
  ```
- For domain types with a jsoniter-scala `JsonValueCodec[A]`, add `zio-nats-jsoniter`. A top-level
  `given fromJsonValueCodec` in `package zio.nats` automatically bridges any `JsonValueCodec[A]` in
  implicit scope to `NatsCodec[A]` — no builder step required. A `NotGiven[NatsCodec[A]]` guard
  prevents it from shadowing built-in codecs (`String`, `Chunk[Byte]`) or any explicit
  `given NatsCodec[A]` already in scope:
  ```scala
  given JsonValueCodec[Person] = JsonCodecMaker.make
  // NatsCodec[Person] resolved automatically via import zio.nats.*
  nats.publish(Subject("persons"), Person("Alice", 30))
  ```
  For an explicit one-off codec, use `NatsCodecJsoniter.fromJsoniter(codec)`.
- For domain types with a play-json `Format[A]`, add `zio-nats-play-json`. A top-level
  `given fromPlayJsonFormat` in `package zio.nats` automatically bridges any `Format[A]` in
  implicit scope to `NatsCodec[A]` — no builder step required. Same `NotGiven[NatsCodec[A]]`
  guard applies:
  ```scala
  given Format[Person] = Json.format[Person]
  // NatsCodec[Person] resolved automatically via import zio.nats.*
  nats.publish(Subject("persons"), Person("Alice", 30))
  ```
  For an explicit one-off codec, use `NatsCodecPlayJson.fromPlayJson(format)`.
- For custom codecs with no framework: implement `NatsCodec[A]` directly (depends only on core).
- Multiple formats can coexist; per-type overrides are plain `given val`s.

#### zio-blocks integration (`zio-nats-zio-blocks`)

`NatsCodecZioBlocks.Builder.derived[A]` is a polymorphic `given` (equivalent to `implicit def`), so
it creates a new instance on every implicit resolution. To avoid redundant re-derivation, `Builder`
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

`NatsCodec.fromFormat` and `NatsCodec.derived` are extension methods defined in
`NatsCodecZioBlocksExtensions` (in `package zio.nats`). They are available via `import zio.nats.*`
when `zio-nats-zio-blocks` is on the classpath — no migration for existing users.

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
- `ServiceError` → `ServiceOperationFailed`, `ServiceStartFailed`, `ServiceCallFailed` (infrastructure errors; domain errors go directly into the ZIO error channel as `Err` via `requestService`)

### Package Structure

The library uses sub-packages for each feature area. Everything is re-exported from `package object nats` so `import zio.nats.*` remains the only import end-users need.

```
zio.nats
├── jetstream/          JetStream service, management, consumer, models, and config
├── kv/                 Key-Value service, management, models, and config
├── objectstore/        Object Store service, management, models, and config
├── service/            NATS Service Framework (Micro protocol) — typed endpoints, discovery, stats
└── config/             NatsConfig (connection settings only)
```

### Service Framework (Micro Protocol)

Endpoints are built via an accumulating builder chain — one consistent entry point for both infallible and fallible handlers:

```
ServiceEndpoint("name")          // NamedEndpoint  — no types yet
  .in[StockRequest]               // EndpointIn[In] — In fixed
  .out[StockReply]                // ServiceEndpoint[In, Nothing, Out] — infallible
  .failsWith[StockError]          // ServiceEndpoint[In, Err, Out]     — single error (optional)
  .failsWith[ErrA, ErrB]          // ServiceEndpoint[In, ErrA | ErrB, Out] — 2-member union
  .failsWith[ErrA, ErrB, ErrC]    // ServiceEndpoint[In, ErrA | ErrB | ErrC, Out] — 3-member union
  .handle { req => ... }          // BoundEndpoint  — handler bound
```

Configuration (`.inGroup`, `.inSubject`, `.withQueueGroup`, `.withMetadata`) is set on `NamedEndpoint` before `.in`. The descriptor (`ServiceEndpoint[In, Err, Out]`) is inert and can be shared between client and server. Handlers run on the ZIO executor via `runtime.unsafe.fork` — jnats dispatcher threads are never blocked.

Key types (all re-exported from `package object nats`):
- `NamedEndpoint` — step 1 of the builder; holds name and non-type config
- `EndpointIn[In]` — step 2; `In` type fixed, waiting for `.out`
- `ServiceEndpoint[In, Err, Out]` — full typed descriptor; call `.handle` or `.handleWith` to get a `BoundEndpoint`; call `.failsWith[E]`, `.failsWith[A, B]`, or `.failsWith[A, B, C]` to add a single or union domain error type
- `ServiceConfig` — name, version, description, metadata for a service
- `ServiceGroup` — subject prefix group for organizing endpoints
- `QueueGroupPolicy` — Default / Disabled / Custom queue group control
- `ServiceErrorMapper[E]` — typeclass converting handler errors to NATS `(message, code)` pairs
- `NatsService` — handle to a running service; provides `ping`, `info`, `stats`, `reset`
- `ServiceDiscovery` — client for cluster-wide service discovery via `$SRV.*`

Opaque types (`Subject`, `QueueGroup`) cannot be moved to sub-packages — re-exporting an opaque type as a plain alias strips its opacity — so they stay in `zio.nats`.

`private[nats]` visibility is preserved across sub-packages: Scala includes sub-packages in its visibility scope.

### Key Files

| File | Contents |
|------|----------|
| `zio-nats-core/src/main/scala/zio/nats/Nats.scala` | Core pub/sub service + `NatsLive`; `live` and `customized` ZLayer constructors; `lifecycleEvents` stream |
| `zio-nats-core/src/main/scala/zio/nats/NatsCodec.scala` | Serialization typeclass; built-in `bytesCodec` and `stringCodec` only (no zio-blocks); `ErrorCodecPart[E]` (per-member, single concrete type only) and `TypedErrorCodec[E]` (wraps single or union via `union2`/`union3`) for service domain-error encoding |
| `zio-nats-zio-blocks/src/main/scala/zio/nats/NatsCodecZioBlocks.scala` | `NatsCodecZioBlocks.Builder` — derives `NatsCodec[A]` from a zio-blocks `Format` + `Schema`; caches in `ConcurrentHashMap` |
| `zio-nats-zio-blocks/src/main/scala/zio/nats/NatsCodecZioBlocksExtensions.scala` | Extension methods `NatsCodec.fromFormat` and `NatsCodec.derived` (available via `import zio.nats.*`) |
| `zio-nats-zio-blocks/src/main/scala/zio/nats/serialization/NatsSerializer.scala` | `CompiledCodec[A]` sealed trait, `makeFor[A](format)` factory (eager, can throw), `BinaryCompiledCodec` / `TextCompiledCodec` impls |
| `zio-nats-jsoniter/src/main/scala/zio/nats/NatsCodecJsoniter.scala` | `NatsCodecJsoniter.wrap` — bridges `JsonValueCodec[A]` to `NatsCodec[A]`; top-level `given fromJsonValueCodec` with `NotGiven[NatsCodec[A]]` guard for automatic resolution |
| `zio-nats-jsoniter/src/main/scala/zio/nats/NatsCodecJsoniter.scala` | `NatsCodecJsoniter.fromJsoniter` — explicit codec wrapper; `wrap` method; `given fromJsonValueCodec` for auto-bridging |
| `zio-nats-play-json/src/main/scala/zio/nats/NatsCodecPlayJson.scala` | `NatsCodecPlayJson.wrap` — bridges play-json `Format[A]` to `NatsCodec[A]`; top-level `given fromPlayJsonFormat` with `NotGiven[NatsCodec[A]]` guard for automatic resolution |
| `zio-nats-play-json/src/main/scala/zio/nats/NatsCodecPlayJson.scala` | `NatsCodecPlayJson.fromPlayJson` — explicit codec wrapper; `wrap` method; `given fromPlayJsonFormat` for auto-bridging |
| `zio-nats-core/src/main/scala/zio/nats/JetStreamEnums.scala` | Scala 3 enums: `AckPolicy`, `DeliverPolicy`, `ReplayPolicy`, `DiscardPolicy`, `RetentionPolicy`, `CompressionOption`, `PriorityPolicy` — each with a `private[nats] def toJava` |
| `zio-nats-core/src/main/scala/zio/nats/NatsError.scala` | Error hierarchy |
| `zio-nats-core/src/main/scala/zio/nats/NatsCoreTypes.scala` | `Subject` (opaque), `QueueGroup` (opaque), `Headers`, `PublishParams`, `StorageType`, `ConnectionStatus`, `NatsServerInfo` |
| `zio-nats-core/src/main/scala/zio/nats/NatsModels.scala` | `Envelope[+A]`, `ConnectionStats` |
| `zio-nats-core/src/main/scala/zio/nats/NatsEvent.scala` | `NatsEvent` enum |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStream.scala` | JetStream publish + `JetStreamLive` |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamManagement.scala` | JetStream stream/consumer admin |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/Consumer.scala` | `Consumer`, `OrderedConsumer` traits + live impls |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamMessage.scala` | `JetStreamMessage` with ack operations |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamModels.scala` | `JsEnvelope`, `PublishAck`, `PublishOptions`, `FetchOptions`, `ConsumeOptions`, summaries, etc. |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamConfig.scala` | `StreamConfig`, `ConsumerConfig`, `OrderedConsumerConfig`, `MirrorConfig`, `SourceConfig`, `ExternalConfig` |
| `zio-nats-core/src/main/scala/zio/nats/kv/KeyValue.scala` | KV service + management; `KeyValue.bucket` (ZIO) and `KeyValue.live` (ZLayer) constructors |
| `zio-nats-core/src/main/scala/zio/nats/kv/KeyValueModels.scala` | `KeyValueEntry`, `KvEnvelope`, `KvEvent`, `KeyValueOperation`, watch options, bucket status |
| `zio-nats-core/src/main/scala/zio/nats/kv/KeyValueConfig.scala` | `KeyValueConfig`, `RepublishConfig` |
| `zio-nats-core/src/main/scala/zio/nats/objectstore/ObjectStore.scala` | ObjectStore service + management; `ObjectStore.bucket` (ZIO) and `ObjectStore.live` (ZLayer) constructors |
| `zio-nats-core/src/main/scala/zio/nats/objectstore/ObjectStoreModels.scala` | `ObjectMeta`, `ObjectData`, `ObjectSummary`, watch options, bucket status |
| `zio-nats-core/src/main/scala/zio/nats/objectstore/ObjectStoreConfig.scala` | `ObjectStoreConfig` |
| `zio-nats-core/src/main/scala/zio/nats/config/NatsConfig.scala` | Connection config — all text-configurable fields; `live` and `fromConfig` ZLayer constructors |
| `zio-nats-core/src/main/scala/zio/nats/config/NatsAuth.scala` | `NatsAuth` enum: `NoAuth`, `Token`, `UserPassword`, `CredentialFile` — mutually exclusive auth methods |
| `zio-nats-core/src/main/scala/zio/nats/config/NatsTls.scala` | `NatsTls` enum: `Disabled`, `SystemDefault`, `KeyStore` — file-based TLS/mTLS config |
| `zio-nats-core/src/main/scala/zio/nats/package.scala` | Type aliases (`NatsIO[A]`), all sub-package re-exports |
| `zio-nats-core/src/main/scala/zio/nats/service/ServiceEndpoint.scala` | `NamedEndpoint`, `EndpointIn[In]`, `ServiceEndpoint[In, Err, Out]`, `BoundEndpoint`, `BoundEndpointLive` |
| `zio-nats-core/src/main/scala/zio/nats/service/ServiceConfig.scala` | `ServiceConfig`, `ServiceGroup`, `QueueGroupPolicy`, `ServiceErrorMapper` |
| `zio-nats-core/src/main/scala/zio/nats/service/ServiceModels.scala` | `ServiceRequest[A]`, `PingResponse`, `InfoResponse`, `StatsResponse`, `EndpointStats`, `EndpointInfo` |
| `zio-nats-core/src/main/scala/zio/nats/service/NatsService.scala` | `NatsService` trait + `NatsServiceLive` |
| `zio-nats-core/src/main/scala/zio/nats/service/ServiceDiscovery.scala` | `ServiceDiscovery` trait + `ServiceDiscoveryLive` + companion |
| `zio-nats-testkit/src/main/scala/zio/nats/NatsTestLayers.scala` | Test layers (Docker NATS) |
