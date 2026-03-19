# Production-Readiness Assessment

Comprehensive review of the zio-nats library's readiness for release as an official ZIO ecosystem NATS library (comparable to zio-kafka, zio-redis).

---

## Current Strengths

The library already does many things well:

1. **Complete NATS feature coverage** — Pub/sub, JetStream (publish, consume with all four strategies), Key-Value, and Object Store are all implemented. More complete than many competing Scala NATS libraries.

2. **Clean ZLayer composition** — The service dependency graph (`NatsConfig -> Nats -> JetStream/KeyValue/ObjectStore`) follows idiomatic ZIO patterns and composes naturally.

3. **Typed codec system with caching** — `NatsCodec[A]` provides compile-time codec resolution with lazy-first-use construction and `ConcurrentHashMap` caching. The `Builder.derived` pattern integrates cleanly with zio-blocks `Schema` + `Format`.

4. **Sealed error ADT** — `NatsError` is a sealed trait hierarchy with sub-sealed traits for JetStream, KeyValue, and ObjectStore errors. All errors extend `NoStackTrace` for performance. The `fromThrowable` converter handles all known jnats exception types.

5. **Single-import API** — `import zio.nats.*` provides access to every type via re-exports in `package.scala`. Users never need sub-package imports.

6. **Good ScalaDocs** — Public traits, methods, and case classes have comprehensive documentation with code examples.

7. **Opaque types for Subject and QueueGroup** — Zero-overhead type safety for subject strings and queue groups, preventing accidental string misuse.

8. **Comprehensive examples** — Four runnable example applications demonstrating pub/sub, JetStream consumption strategies, KV operations, and Object Store usage.

9. **Testkit subproject** — `NatsTestLayers` is published separately so downstream users can write their own integration tests against containerized NATS.

10. **Request-reply service** — `NatsRpc.respond` provides a ready-made pattern for building typed RPC services over NATS.

---

## P0 — Release Blockers

These must be resolved before any public release.

### Infrastructure

#### P0-1: CI/CD pipeline

No GitHub Actions workflows exist. The `.github/workflows` directory is missing entirely. Needs at minimum:
- Compile + test on PR
- scalafmt check on PR
- Publish-on-tag to Maven Central
- Test matrix for supported JDK versions

#### P0-2: Maven Central publishing

No `sbt-ci-release` or `sbt-sonatype` plugin in `project/plugins.sbt` (currently only `sbt-scalafmt` and `sbt-scoverage`). Needs GPG signing setup, POM metadata (developers, SCM info), and Sonatype credentials in CI secrets.

#### P0-3: Binary compatibility checking (MiMa)

No `sbt-mima-plugin`. Should be added now so it is in place from version 0.1.0 onward, preventing accidental binary-incompatible changes in future releases.

### API Purity — jnats Type Leaks

#### P0-4: Java enum type aliases leak jnats types

In `package.scala` (lines 29–77), `AckPolicy`, `DeliverPolicy`, `ReplayPolicy`, `DiscardPolicy`, `RetentionPolicy`, `CompressionOption`, and `PriorityPolicy` are `type` aliases to `io.nats.client.api.*` Java enums. While companion objects re-export values, the types themselves are raw Java enums. Code like `val p: AckPolicy = AckPolicy.Explicit` compiles, but `p` is an `io.nats.client.api.AckPolicy` at the type level.

**Fix:** Replace with proper Scala 3 enums and `private[nats]` conversion methods to/from jnats enums.

#### P0-5: `NatsConfig.authHandler` exposes `io.nats.client.AuthHandler` — WON'T DO

`AuthHandler` is a jnats interface that users must implement to provide dynamic credentials. There is no way to fully hide it — any wrapper would just move the jnats import to the construction site. The field is only needed for advanced/programmatic auth; users relying on `credentialPath`, `token`, or `username`/`password` never encounter it.

#### P0-6: `NatsConfig.optionsCustomizer` exposes `io.nats.client.Options.Builder` — WON'T DO

`optionsCustomizer` is an intentional escape hatch. The type leak cannot be removed without removing the escape hatch itself. Renaming it would be cosmetic only and add churn without fixing the underlying dependency.

### ZIO Ecosystem Conventions

#### P0-7: No ZIO accessor methods on companion objects

Every official ZIO library provides `ZIO.serviceWithZIO`-based accessor methods so users write `Nats.publish(subject, value)` instead of `ZIO.serviceWithZIO[Nats](_.publish(subject, value))`.

**Fix:** Add accessor methods to `Nats`, `JetStream`, `KeyValue`, `KeyValueManagement`, `ObjectStore`, and `ObjectStoreManagement` companion objects.

#### P0-8: `KeyValue.bucket()` and `ObjectStore.bucket()` return `ZIO`, not `ZLayer`

Other ZIO ecosystem libraries provide `ZLayer` constructors for sub-services. Currently `KeyValue.bucket("name")` returns `ZIO[Nats, NatsError, KeyValue]`.

**Fix:** Provide both the `ZIO` variant (for programmatic use) and a `ZLayer` variant (e.g., `KeyValue.live("name"): ZLayer[Nats, NatsError, KeyValue]`).

---

## P1 — Should Fix Before or Shortly After 1.0

### API Design

#### P1-1: Hardcoded 2-second default request timeout

In `Nats.scala` (line 85), the convenience overload `request[A, B](subject, request)` hardcodes a 2-second timeout. Should either be configurable via `NatsConfig` or removed in favor of always requiring an explicit timeout.

#### P1-2: `Nats.underlying` returns raw `JConnection` as a `def`

In `Nats.scala` (line 154). While intentionally an escape hatch, returning a synchronous value is inconsistent with the rest of the effectful API. Consider wrapping in `UIO[JConnection]` or adding a prominent ScalaDoc warning.

#### P1-3: `toNatsData` extension on String

In `package.scala` (lines 19–22). This extension method is redundant since `NatsCodec[String]` handles encoding. Adds API surface without clear benefit; should be deprecated or removed.

### Integration

#### P1-4: No ZIO Config integration

`NatsConfig` is a plain case class. Official ZIO libraries use `zio-config` for automatic derivation from HOCON, environment variables, and system properties. Users should be able to write `NatsConfig.fromConfig` or derive a `ConfigDescriptor[NatsConfig]`.

#### P1-5: No metrics integration

No ZIO-native metrics for message counts, publish/subscribe latency, connection status, or reconnection counts. Official ZIO libraries integrate with `zio-metrics-connectors`.

#### P1-6: No distributed tracing

No OpenTelemetry / ZIO Telemetry integration. For production microservices, trace context propagation through NATS headers is essential.

### Test Quality

#### P1-7: Sleep-based test synchronization

25 instances of `ZIO.sleep(200–500.millis)` across test files (`NatsPubSubSpec.scala`, `NatsRpcSpec.scala`, `KeyValueSpec.scala`, `ObjectStoreSpec.scala`, `NatsErrorSpec.scala`). Causes flaky tests and slow CI. Should use `Promise`, `Ref`, `TestClock`, or subscription-ready signals.

#### P1-8: No lifecycle event tests

`Nats.lifecycleEvents` is part of the public API but has zero integration tests. Should test: connect event on startup, disconnect/reconnect events on server restart, lame-duck mode.

#### P1-9: No reconnection tests

No tests verifying behavior when the NATS server drops and comes back. Should use testcontainers to stop/start the NATS container mid-test.

#### P1-10: No concurrent stress tests

All tests run sequentially with single producers and consumers. No tests for concurrent producers/consumers, backpressure behavior, or high-throughput scenarios.

### Code Quality

#### P1-11: `MessageTtl.seconds(d.toSeconds.toInt)` truncates Long to Int

In `KeyValue.scala` (lines 288, 317–319). `Duration.toSeconds` returns `Long`; `.toInt` silently truncates. For durations over ~68 years this would overflow. Should validate or use a safe conversion.

#### P1-12: `consumeKeys` LinkedBlockingQueue has no cancellation

In `KeyValue.scala` (lines 411–424). The `LinkedBlockingQueue` returned by jnats `kv.consumeKeys()` is acquired in `ZStream.unwrap` but not wrapped in `acquireRelease`. If the stream is interrupted, the jnats-side iterator may leak resources.

---

## P2 — Nice to Have

### Ecosystem Breadth

#### P2-1: Scala 2.13 cross-compilation

Currently Scala 3 only (`crossScalaVersions := Seq(scala3)` in `build.sbt`). Many ZIO ecosystem users remain on 2.13. The library uses Scala 3 features (opaque types, enums, extension methods, `given`/`using`) extensively, making cross-compilation non-trivial.

#### P2-2: Documentation site

No mdoc, Laika, or microsite setup. Official ZIO libraries have generated documentation sites hosted on GitHub Pages.

#### P2-3: Scalafix rules

`sbt-scalafmt` is configured but no scalafix rules are present. Could provide migration rules for future breaking changes.

### Feature Gaps

#### P2-4: Graceful shutdown / drain integration

Connection lifecycle is tied to `ZLayer` scope, but no integration with ZIO's graceful shutdown signal. Applications should be able to drain subscriptions before the connection layer is torn down.

#### P2-5: More specific ObjectStore errors

`ObjectStoreError` currently only has `ObjectStoreOperationFailed`. Could benefit from `ObjectNotFound`, `ObjectAlreadyExists`, etc., matching the KV error model which has `KeyNotFound`.

#### P2-6: `ConsumerConfig.startTime` uses `java.time.ZonedDateTime`

In `JetStreamConfig.scala`. While `java.time` types are standard in Scala, some users may prefer `java.time.Instant`. Minor and cosmetic.

#### P2-7: Request-reply service could be richer

`NatsRpc` exists but is minimal. A full service abstraction would include routing (multiple handlers on different subjects), typed error propagation, and timeout configuration per handler.

---

## Implementation Roadmap

### Phase 1 — P0 (pre-release)

Suggested order:

1. **P0-1, P0-2, P0-3** — CI/CD, publishing, MiMa (infrastructure first)
2. **P0-4** — Scala 3 enums for policies (largest API change, affects many files)
3. **P0-5, P0-6** — NatsConfig jnats leaks (localized to one file)
4. **P0-7** — ZIO accessor methods (mechanical but touches every service companion)
5. **P0-8** — ZLayer variants for bucket services (small addition)

### Phase 2 — P1 (before or shortly after 1.0)

Suggested order:

1. **P1-7** — Fix sleep-based tests (improves development velocity)
2. **P1-8, P1-9, P1-10** — Test coverage gaps
3. **P1-11, P1-12** — Code quality fixes
4. **P1-1, P1-2, P1-3** — API refinements
5. **P1-4, P1-5, P1-6** — Integrations (can be separate modules)

### Phase 3 — P2 (1.x maintenance)

Address as capacity allows. P2-1 (Scala 2.13) and P2-2 (docs site) have the highest impact in this tier.

---

## Key Files Referenced

| File | Relevant Items |
|------|---------------|
| `zio-nats/src/main/scala/zio/nats/package.scala` | P0-4 (Java enum aliases, lines 29–77), P1-3 (`toNatsData`, lines 19–22) |
| `zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala` | P0-5 (`authHandler`, line 75), P0-6 (`optionsCustomizer`, line 82) |
| `zio-nats/src/main/scala/zio/nats/Nats.scala` | P0-7 (accessor methods), P1-1 (hardcoded timeout, line 85), P1-2 (`underlying`, line 154) |
| `zio-nats/src/main/scala/zio/nats/kv/KeyValue.scala` | P0-8 (ZLayer variant), P1-11 (Long→Int truncation, lines 288/317–319), P1-12 (`consumeKeys` leak, lines 411–424) |
| `zio-nats/src/main/scala/zio/nats/jetstream/JetStreamConfig.scala` | P2-6 (`ConsumerConfig.startTime`) |
| `project/plugins.sbt` | P0-2 (sbt-ci-release), P0-3 (sbt-mima-plugin) |
