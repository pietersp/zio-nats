---
id: modules
title: Modules
---

# Modules

> Which artifact to add to `build.sbt` and what each one includes.

## Quick reference

```scala
// ── Option A: batteries-included ────────────────────────────────────────────
// Pub/sub, JetStream, KV, Object Store, Service Framework,
// and zio-blocks type-safe serialization.
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"

// ── Option B: core only (no zio-blocks) ─────────────────────────────────────
// Use when you prefer jsoniter-scala or a fully custom NatsCodec[A].
libraryDependencies += "io.github.pietersp" %% "zio-nats-core" % "@VERSION@"

// ── Optional: jsoniter-scala serialization ───────────────────────────────────
// Pair with zio-nats-core, or add alongside zio-nats to use jsoniter
// for selected types while keeping zio-blocks for others.
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "@VERSION@"

// ── Optional: play-json serialization ────────────────────────────────────────
// Pair with zio-nats-core, or add alongside zio-nats.
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "@VERSION@"

// ── Testkit ──────────────────────────────────────────────────────────────────
// Integration test helpers — starts a NATS container via testcontainers.
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "@VERSION@" % Test
```

## Module details

| Artifact | What it includes | When to use it |
|---|---|---|
| `zio-nats` | `zio-nats-core` + `zio-nats-zio-blocks` | Most projects — gets everything including typed serialization via zio-blocks |
| `zio-nats-core` | Pub/sub, JetStream, KV, Object Store, Service Framework, `String`/`Chunk[Byte]` codecs only | When you want jsoniter-scala, play-json, or a fully custom codec instead of zio-blocks |
| `zio-nats-zio-blocks` | `NatsCodec.fromFormat`, `Schema`-based codec derivation | Transitive via `zio-nats`; add explicitly only when using `zio-nats-core` |
| `zio-nats-jsoniter` | `NatsCodecJsoniter`, `given fromJsonValueCodec` auto-bridge | High-performance JSON; alternative or complement to zio-blocks |
| `zio-nats-play-json` | `NatsCodecPlayJson`, `given fromPlayJsonFormat` auto-bridge | Play framework projects; alternative or complement to zio-blocks |
| `zio-nats-testkit` | `NatsTestLayers.nats` — testcontainers-based `Nats` layer | Integration tests |

## Dependency graph

```
zio-nats
  └── zio-nats-core
  └── zio-nats-zio-blocks
        └── zio-nats-core

zio-nats-jsoniter
  └── zio-nats-core (declared; you provide it via zio-nats or zio-nats-core)

zio-nats-play-json
  └── zio-nats-core (declared; you provide it via zio-nats or zio-nats-core)

zio-nats-testkit
  └── zio-nats-core
```

## Serialization module combinations

| Setup | Artifacts |
|---|---|
| zio-blocks JSON only | `zio-nats` |
| jsoniter only | `zio-nats-core` + `zio-nats-jsoniter` |
| play-json only | `zio-nats-core` + `zio-nats-play-json` |
| zio-blocks + jsoniter (selected types) | `zio-nats` + `zio-nats-jsoniter` |
| zio-blocks + play-json (selected types) | `zio-nats` + `zio-nats-play-json` |
| All three | `zio-nats` + `zio-nats-jsoniter` + `zio-nats-play-json` |

## Additional zio-blocks formats

The batteries-included `zio-nats` artifact brings in `zio-blocks-schema` (JSON) transitively.
For other formats add the corresponding zio-blocks artifact alongside it:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack" % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"  % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"    % "<zio-blocks-version>"
```
