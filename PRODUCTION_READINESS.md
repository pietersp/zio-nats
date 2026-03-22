# Production-Readiness Assessment

Comprehensive review of the zio-nats library's readiness for release as a production-ready ZIO 2 NATS client library.

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

10. **Service Framework (Micro protocol)** — `ServiceEndpoint` / `NatsService` / `ServiceDiscovery` implement the full NATS Micro protocol with typed endpoints, queue-group policies, stats, and cluster-wide discovery.

---

## P0 — Release Blockers

These must be resolved before any public release.

### Infrastructure

#### ~~P0-1: CI/CD pipeline~~ **DONE**

GitHub Actions workflows created:
- `.github/workflows/ci.yml` — compile, test, and scalafmt check on PR
- `.github/workflows/release.yml` — publish-on-tag to Maven Central via sbt-ci-release
- `.github/workflows/mima.yml` — binary compatibility check on PR (existing)

sbt-ci-release plugin added to `project/plugins.sbt`. POM metadata (developers, SCM info) added to `build.sbt`.

#### ~~P0-2: Maven Central publishing~~ **DONE**

`sbt-ci-release` plugin added to `project/plugins.sbt`. `release.yml` workflow created. POM metadata (developers, SCM info) added to `build.sbt`.

- GPG signing subkey exported and stored as `PGP_SECRET` / `PGP_PASSPHRASE` in GitHub Actions secrets. Public key uploaded to `keyserver.ubuntu.com`.
- Sonatype Central Portal account created via GitHub login. `io.github.pietersp` namespace verified automatically. User token stored as `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` in GitHub Actions secrets.
- `sbt-ci-release 1.11.2` targets the new Central Portal API (legacy OSSRH sunset June 2025).

**Note:** `mimaPreviousArtifacts` is currently `Set.empty` on all subprojects. After the first release, update each to the actual published version (e.g. `"io.github.pietersp" %% "zio-nats-core" % "0.1.0"`) so MiMa has a baseline to compare against.

#### ~~P0-3: Binary compatibility checking (MiMa)~~ **DONE**

`sbt-mima-plugin` added to `project/plugins.sbt`. Enabled on all 5 published subprojects (`zio-nats-core`, `zio-nats-zio-blocks`, `zio-nats-jsoniter`, `zio-nats-play-json`, `zio-nats-testkit`). GitHub Actions workflow created at `.github/workflows/mima.yml`.

**Important:** `mimaPreviousArtifacts` is currently `Set.empty` on all subprojects. Before the first public release, update each to the actual version (e.g. `Set("io.github.pietersp" %% "zio-nats-core" % "0.1.0")`). Without this, MiMa has no baseline to compare against and will not detect regressions.

### API Purity — jnats Type Leaks

#### P0-4: Java enum type aliases leak jnats types — DONE

~~In `package.scala` (lines 29–77), `AckPolicy`, `DeliverPolicy`, `ReplayPolicy`, `DiscardPolicy`, `RetentionPolicy`, `CompressionOption`, and `PriorityPolicy` are `type` aliases to `io.nats.client.api.*` Java enums. While companion objects re-export values, the types themselves are raw Java enums. Code like `val p: AckPolicy = AckPolicy.Explicit` compiles, but `p` is an `io.nats.client.api.AckPolicy` at the type level.~~

Replaced with proper Scala 3 enums in `zio-nats-core/src/main/scala/zio/nats/JetStreamEnums.scala`. Each enum has a `private[nats] def toJava` conversion. The Java enum type aliases and companion objects in `package.scala` have been removed. `JetStreamConfig.scala` updated to call `.toJava` at every builder call site.

#### P0-5: `NatsConfig.authHandler` exposes `io.nats.client.AuthHandler` — WON'T DO

`AuthHandler` is a jnats interface that users must implement to provide dynamic credentials. There is no way to fully hide it — any wrapper would just move the jnats import to the construction site. The field is only needed for advanced/programmatic auth; users relying on `credentialPath`, `token`, or `username`/`password` never encounter it.

#### P0-6: `NatsConfig.optionsCustomizer` exposes `io.nats.client.Options.Builder` — WON'T DO

`optionsCustomizer` is an intentional escape hatch. The type leak cannot be removed without removing the escape hatch itself. Renaming it would be cosmetic only and add churn without fixing the underlying dependency.

### ZIO Ecosystem Conventions

#### P0-7: No ZIO accessor methods on companion objects — WON'T DO

~~Every official ZIO library provides `ZIO.serviceWithZIO`-based accessor methods so users write `Nats.publish(subject, value)` instead of `ZIO.serviceWithZIO[Nats](_.publish(subject, value))`.~~

Accessor methods have been [officially deprecated by the ZIO team](https://zio.dev/reference/service-pattern/accessor-methods/#why-are-accessor-methods-deprecated). They cause confusing type errors (the error points to the accessor call site rather than the service method), and `ZIO.serviceWithZIO[Nats](_.publish(...))` is now the idiomatic approach. We will not add accessor methods.

#### ~~P0-8: `KeyValue.bucket()` and `ObjectStore.bucket()` return `ZIO`, not `ZLayer`~~ **DONE**

Other ZIO ecosystem libraries provide `ZLayer` constructors for sub-services. Currently `KeyValue.bucket("name")` returns `ZIO[Nats, NatsError, KeyValue]`.

**Fix:** Provide both the `ZIO` variant (for programmatic use) and a `ZLayer` variant (e.g., `KeyValue.live("name"): ZLayer[Nats, NatsError, KeyValue]`).

---

## P1 — Should Fix Before or Shortly After 1.0

### API Design

#### ~~P1-1: Hardcoded 2-second default request timeout~~ **DONE**

~~In `Nats.scala` (line 85), the convenience overload `request[A, B](subject, request)` hardcodes a 2-second timeout. Should either be configurable via `NatsConfig` or removed in favor of always requiring an explicit timeout.~~

Removed the no-timeout overload entirely. All call sites must now supply an explicit `timeout: Duration`.

#### ~~P1-2: `Nats.underlying` returns raw `JConnection` as a `def`~~ **DONE**

~~In `Nats.scala` (line 154). While intentionally an escape hatch, returning a synchronous value is inconsistent with the rest of the effectful API. Consider wrapping in `UIO[JConnection]` or adding a prominent ScalaDoc warning.~~

Added a prominent ScalaDoc warning on `underlying` documenting it as an escape hatch only and cautioning against direct jnats use. Changing the type to `UIO[JConnection]` was deferred — it would cascade into all internal library call sites (`KeyValue`, `ObjectStore`, `JetStream`, `JetStreamManagement`) and is better addressed as a separate refactor if ever needed.

#### ~~P1-3: `toNatsData` extension on String~~ **DONE**

~~In `package.scala` (lines 19–22). This extension method is redundant since `NatsCodec[String]` handles encoding. Adds API surface without clear benefit; should be deprecated or removed.~~

Removed. README examples that used `order.toNatsData` (which was already incorrect — it was only defined on `String`) updated to use direct typed publish.

### Integration

#### ~~P1-4: No ZIO Config integration~~ **DONE**

`NatsConfig.config` — a `zio.Config[NatsConfig]` descriptor — and `NatsConfig.fromConfig` — a `ZLayer[Any, Config.Error, NatsConfig]` — are implemented in `config/NatsConfig.scala`. No new dependency: `zio.Config` is in `zio-core`.

- All fields covered with defaults matching the case class defaults.
- Auth discriminated via `auth.type` (`no-auth` | `token` | `user-password` | `credential-file`); unknown type or missing required sub-field produces a single targeted `Config.Error` with the key name in the message.
- TLS discriminated via `tls.type` (`disabled` | `system-default` | `key-store`); same error quality.
- `NatsAuth.Custom` and `NatsTls.Custom` are intentionally excluded (they hold runtime Java objects).
- `NatsAuth.Keys` / `NatsTls.Keys` companion objects hold all type-key strings as a single source of truth. `configTypeKey` exhaustive methods on each enum provide a compile-time guard: adding a new variant without updating the config layer produces a non-exhaustive match error.
- Server URLs validated at load time via `mapOrFail` — invalid URLs produce `Config.Error.InvalidData` before any connection attempt.
- All duration / timeout fields use ISO-8601 format (`PT5S`, `PT2M`, `PT0.5S`); `socketReadTimeout` maps to jnats's int-millis overload internally (documented at call site).
- `Config.zip` in ZIO 2 produces flat tuples via `Zippable`; the implementation uses private case classes (`ConnGroup`, `TunGroup`) to group fields before the final combine, avoiding `Zippable` flatten/nesting ambiguity. `drainTimeout` is in `ConnGroup` (connection lifecycle, not tuning).
- `nested` call order documented: last call = outermost namespace, so `.nested("nats").nested("myapp")` reads `myapp.nats.*`.
- 24 offline unit tests in `NatsConfigSpec.scala` (no Docker required).

**Key env var examples:**
```
NATS_SERVERS=nats://broker:4222
NATS_AUTH_TYPE=token
NATS_AUTH_VALUE=s3cr3t
NATS_CONNECTION_TIMEOUT=PT5S
NATS_TLS_TYPE=system-default
```

#### P1-5: No metrics integration

No ZIO-native metrics for message counts, publish/subscribe latency, connection status, or reconnection counts. Official ZIO libraries integrate with `zio-metrics-connectors`.

#### P1-6: No distributed tracing

No OpenTelemetry / ZIO Telemetry integration. For production microservices, trace context propagation through NATS headers is essential.

### Test Quality

#### P1-7: Sleep-based test synchronization — WON'T DO (requires library API change)

Many instances of `ZIO.sleep(200–500.millis)` across test files (`NatsPubSubSpec.scala`, `KeyValueSpec.scala`, `ObjectStoreSpec.scala`, `NatsErrorSpec.scala`, `ServiceSpec.scala`).

**Investigation revealed:** These sleeps are **required** — not a code smell. NATS subscriptions require server-side registration before messages can be delivered. The jnats client sends a SUB protocol message but receives no acknowledgment. There is no callback or polling mechanism available to detect when registration completes.

The existing sleeps are:
- **Small**: 200–500ms (not 2–5 seconds)
- **Deterministic**: Tests pass consistently both locally and in CI
- **Necessary**: Without them, tests time out because the subscription isn't ready when publish occurs

**Why `Promise`/`Ref`/`TestClock` won't help:** These are for coordinating within ZIO, but the problem is inter-process (JVM → NATS server). We need a signal from the NATS server that the subscription is registered.

**What would be needed to fix properly:** A new `Nats.subscribeWithReady` API that returns both a `ZStream` and a `Promise` that completes when the server confirms subscription registration. This requires changes to the library's core API and jnats interop layer — beyond the scope of test-only fixes.

**Current status:** Retained necessary sleeps. Tests are reliable, not flaky. CI is slow due to NATS container startup (~10s) and sequential test execution, not the sleeps themselves.

#### P1-8: No lifecycle event tests

`Nats.lifecycleEvents` is part of the public API but has zero integration tests. Should test: connect event on startup, disconnect/reconnect events on server restart, lame-duck mode.

#### P1-9: No reconnection tests

No tests verifying behavior when the NATS server drops and comes back. Should use testcontainers to stop/start the NATS container mid-test.

#### P1-10: No concurrent stress tests

All tests run sequentially with single producers and consumers. No tests for concurrent producers/consumers, backpressure behavior, or high-throughput scenarios.

### Code Quality

#### ~~P1-11: `MessageTtl.seconds(d.toSeconds.toInt)` truncates Long to Int~~ **DONE**

In `KeyValue.scala` (lines 288, 317–319). `Duration.toSeconds` returns `Long`; `.toInt` silently truncates. Fixed by adding `toMessageTtl(d)` helper that clamps to `Int.MaxValue` seconds (~68 years max TTL). Longer durations are clamped to the maximum.

#### P1-12: `consumeKeys` LinkedBlockingQueue has no cancellation — WON'T DO

In `KeyValue.scala` (lines 411–424). Initial concern was that the `LinkedBlockingQueue` acquired in `ZStream.unwrap` has no cancellation safety — if the stream is interrupted, the jnats-side iterator may leak resources.

**Investigation revealed:** `consumeKeys()` is a **finite, bounded operation** — it fetches all current keys and returns, not a continuous watcher. The jnats implementation processes all pending keys then queues a sentinel (`isDone = true`) and exits. The `finally { sub.unsubscribe(); }` block ensures the subscription is cleaned up when `visitSubject` returns. Both `keys()` and `consumeKeys()` provide identical semantics for the same use case; `consumeKeys()` offers a ZStream-friendly API for composability with stream operators. No resource leak exists under normal interruption.

---

## P2 — Nice to Have

### Ecosystem Breadth

#### P2-1: Scala 2.13 cross-compilation — WON'T DO

The library uses Scala 3 features (opaque types, enums, extension methods, `given`/`using`) extensively throughout the codebase. Cross-compilation would require a near-complete rewrite with no clear benefit — the ZIO 2 ecosystem has largely moved to Scala 3.

#### ~~P2-2: Documentation site~~ **DONE**

mdoc + Docusaurus 3.x scaffolded. GitHub Actions workflow builds and deploys to GitHub Pages on every push to `master`. Site live at `https://pietersp.github.io/zio-nats/`. Unified Scaladoc generated via sbt-unidoc and linked from the navbar. Content is placeholder — to be filled in.

#### P2-3: Scalafix rules — WON'T DO

No breaking changes have occurred yet, so there is nothing to migrate. If breaking changes are introduced in the future, migration rules can be added at that point.

### Feature Gaps

#### ~~P2-4: Graceful shutdown / drain integration~~ **DONE**

`NatsConfig.drainTimeout` (default 30s) controls the drain timeout. When the `Nats.live` ZLayer's scope ends, the connection is automatically drained (with that timeout) before closing — no manual intervention required. The `drain()` method has been removed from the `Nats` trait since drain is now handled automatically by the ZLayer scope.

#### ~~P2-5: More specific ObjectStore errors~~ **DONE**

`ObjectStoreError` now has `ObjectNotFound` and `ObjectAlreadyExists`, matching the KV error model which has `KeyNotFound`.

#### ~~P2-6: `ConsumerConfig.startTime` uses `java.time.ZonedDateTime`~~ **DONE**

`ConsumerConfig.startTime` and `OrderedConsumerConfig.startTime` now use `java.time.Instant` instead of `java.time.ZonedDateTime`. Converted to `ZonedDateTime` (UTC) when calling jnats.

---

## Implementation Roadmap

### Phase 1 — P0 (pre-release)

Suggested order:

1. ~~**P0-1** — CI/CD pipeline~~ **DONE**
2. ~~**P0-2** — Maven Central publishing~~ **DONE**
2. ~~**P0-4** — Scala 3 enums for policies (largest API change, affects many files)~~ **DONE**
3. ~~**P0-5, P0-6** — NatsConfig jnats leaks (localized to one file)~~ **WON'T DO** (see above)
4. ~~**P0-7** — ZIO accessor methods (mechanical but touches every service companion)~~ **WON'T DO** (officially deprecated by ZIO team)
5. ~~**P0-8** — ZLayer variants for bucket services (small addition)~~ **DONE**

### Phase 2 — P1 (before or shortly after 1.0)

Suggested order:

1. ~~**P1-7** — Fix sleep-based tests~~ **WON'T DO** (requires library API change to add subscription-ready detection)
2. **P1-8, P1-9, P1-10** — Test coverage gaps
4. **P1-1, P1-2, P1-3** — API refinements
5. **P1-4, P1-5, P1-6** — Integrations (can be separate modules)

### Phase 3 — P2 (1.x maintenance)

~~P2-2 (docs site)~~ **DONE**. Remaining P2 items are low priority.

---

## Key Files Referenced

| File | Relevant Items |
|------|---------------|
| `zio-nats-core/src/main/scala/zio/nats/package.scala` | ~~P0-4 (Java enum aliases, lines 29–77)~~ (done), ~~P1-3 (`toNatsData`)~~ (done) |
| `zio-nats-core/src/main/scala/zio/nats/config/NatsConfig.scala` | P0-5 (`authHandler`, line 75), P0-6 (`optionsCustomizer`, line 82), ~~P2-4 (`drainTimeout`)~~ (done) |
| `zio-nats-core/src/main/scala/zio/nats/Nats.scala` | ~~P0-7 (accessor methods)~~ (won't do), ~~P1-1 (hardcoded timeout)~~ (done), ~~P1-2 (`underlying` warning)~~ (done), ~~P2-4 (drain on scope exit)~~ (done) |
| `zio-nats-core/src/main/scala/zio/nats/kv/KeyValue.scala` | P0-8 (ZLayer variant), ~~P1-11 (Long→Int truncation)~~ (done), ~~P1-12 (`consumeKeys` leak)~~ (won't do) |
| `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamConfig.scala` | P2-6 (`ConsumerConfig.startTime`) |
| `project/plugins.sbt` | P0-1 (sbt-ci-release, done), P0-3 (sbt-mima-plugin, done) |
| `.github/workflows/ci.yml` | P0-1 CI workflow (compile, test, scalafmt) |
| `.github/workflows/release.yml` | P0-1 release workflow (publish-on-tag) |
| `.github/workflows/mima.yml` | P0-3 MiMa workflow |
