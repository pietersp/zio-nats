---
id: index
title: zio-nats
slug: /
---

# zio-nats

A purely functional [NATS](https://nats.io) client for [ZIO 2](https://zio.dev), built on top of the official [jnats](https://github.com/nats-io/nats.java) Java client.

## Features

- **Idiomatic ZIO 2** — every subsystem is a `ZLayer`; lifecycle is managed automatically
- **Full API coverage** — core pub/sub, request-reply, JetStream, Key-Value, Object Store, and the NATS Service (Micro) framework
- **`ZStream`-based** — subscriptions and consumers are streams; no callbacks in user code
- **Typed errors** — `NatsError` is a sealed ADT; no raw exceptions at the boundary
- **Type-safe serialization** — built-in codecs for `String` and `Chunk[Byte]`; optional integrations for [zio-blocks](https://zio.dev/zio-blocks), [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala), and [play-json](https://github.com/playframework/play-json)
- **Zero jnats types** — `import zio.nats.*` is the only import your application code needs
- **Scala 3**

## Installation

```scala
// Batteries-included: pub/sub, JetStream, KV, Object Store, Service Framework,
// and zio-blocks type-safe serialization.
libraryDependencies += "io.github.pietersp" %% "zio-nats" % "@VERSION@"
```

See the [Quick start](quickstart.md) page for full installation options including
`zio-nats-core`, `zio-nats-jsoniter`, and `zio-nats-play-json`.

## Service hierarchy

`Nats` is the root service. All other services are derived from it:

```
NatsConfig
  └── Nats.live            ← core connection (pub/sub / request-reply)
        ├── JetStream.live
        ├── JetStreamManagement.live
        ├── KeyValue.live(bucketName)
        ├── KeyValueManagement.live
        ├── ObjectStore.live(bucketName)
        └── ObjectStoreManagement.live
```

Wire services with `>+>` to keep all prior layers in scope:

```scala
val appLayer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStream.live >+>
  KeyValueManagement.live
```
