---
id: modules
title: Modules
---

zio-nats is split across several artifacts. Most projects need only one of the two main options below - `zio-nats` for batteries-included, or `zio-nats-core` when you want to bring your own serialization.

## Artifact coordinates

```scala
// Option A: batteries-included
// Pub/sub, JetStream, KV, Object Store, Service Framework,
// and zio-blocks type-safe serialization.
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"

// Option B: core only (no zio-blocks)
// Use when you prefer jsoniter-scala, play-json, zio-json, or a fully custom NatsCodec[A].
libraryDependencies += "io.github.pietersp" %% "zio-nats-core" % "@VERSION@"

// Optional: jsoniter-scala serialization
// Pair with zio-nats-core, or add alongside zio-nats for selected types.
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "@VERSION@"

// Optional: play-json serialization
// Pair with zio-nats-core, or add alongside zio-nats for selected types.
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "@VERSION@"

// Optional: zio-json serialization
// Pair with zio-nats-core, or add alongside zio-nats for selected types.
libraryDependencies += "io.github.pietersp" %% "zio-nats-zio-json" % "@VERSION@"

// Testkit: starts a real NATS server in Docker via testcontainers
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "@VERSION@" % Test
```

## Module details

| Artifact              | What it includes                                                                | When to use                                                             |
|-----------------------|---------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `zio-nats`            | `zio-nats-core` + `zio-nats-zio-blocks`                                        | Most projects - gets everything including zio-blocks serialization      |
| `zio-nats-core`       | Pub/sub, JetStream, KV, Object Store, Service Framework, `String`/`Chunk[Byte]` codecs | When you want jsoniter-scala, play-json, zio-json, or a fully custom codec |
| `zio-nats-zio-blocks` | `NatsCodec.fromFormat`, `Schema`-based codec derivation                         | Transitive via `zio-nats`; add explicitly only when using `zio-nats-core` |
| `zio-nats-jsoniter`   | `NatsCodecJsoniter`, `given fromJsonValueCodec` auto-bridge                     | High-performance JSON; alternative or complement to zio-blocks          |
| `zio-nats-play-json`  | `NatsCodecPlayJson`, `given fromPlayJsonFormat` auto-bridge                     | Play framework projects; alternative or complement to zio-blocks        |
| `zio-nats-zio-json`   | `NatsCodecZioJson`, `given fromZioJson` auto-bridge                             | ZIO-native projects; alternative or complement to zio-blocks            |
| `zio-nats-testkit`    | `NatsTestLayers.nats` - testcontainers-based `Nats` layer                       | Integration tests                                                       |

## Dependency graph

```
zio-nats
  └── zio-nats-core
  └── zio-nats-zio-blocks
        └── zio-nats-core

zio-nats-jsoniter
  └── zio-nats-core  (provided by zio-nats or zio-nats-core)

zio-nats-play-json
  └── zio-nats-core  (provided by zio-nats or zio-nats-core)

zio-nats-zio-json
  └── zio-nats-core  (provided by zio-nats or zio-nats-core)

zio-nats-testkit
  └── zio-nats-core
```

## Serialization combinations

| Serialization setup                          | Artifacts                                         |
|----------------------------------------------|---------------------------------------------------|
| zio-blocks JSON only                         | `zio-nats`                                        |
| jsoniter-scala only                          | `zio-nats-core` + `zio-nats-jsoniter`             |
| play-json only                               | `zio-nats-core` + `zio-nats-play-json`            |
| zio-json only                                | `zio-nats-core` + `zio-nats-zio-json`             |
| zio-blocks + jsoniter for selected types     | `zio-nats` + `zio-nats-jsoniter`                  |
| zio-blocks + play-json for selected types    | `zio-nats` + `zio-nats-play-json`                 |
| zio-blocks + zio-json for selected types     | `zio-nats` + `zio-nats-zio-json`                  |

## Additional zio-blocks formats

The `zio-nats` artifact brings in `zio-blocks-schema` (JSON) transitively. For binary formats, add the corresponding zio-blocks artifact:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack" % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"  % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"    % "<zio-blocks-version>"
```

More formats are likely to be added as zio-blocks grows. See the [zio-blocks documentation](https://zio.dev/zio-blocks/) for the current list of supported formats.
