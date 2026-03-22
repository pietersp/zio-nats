---
id: architecture
title: Architecture
---

# Architecture

> How all zio-nats services relate to each other and to `ZLayer`.

All zio-nats services are plain Scala traits backed by `ZLayer` constructors. They form a
tree rooted at `Nats` — every other service is derived from it.

## The service graph

```
NatsConfig
    └── Nats.live                   ← core connection (pub/sub, request-reply)
            ├── JetStream.live              ← persistent publishing + consumer access
            ├── JetStreamManagement.live    ← stream + consumer administration
            ├── KeyValueManagement.live     ← KV bucket administration
            ├── KeyValue.live(name)         ← KV bucket service (ZLayer)
            ├── ObjectStoreManagement.live  ← Object Store administration
            └── ObjectStore.live(name)      ← Object Store bucket service (ZLayer)
```

`Nats` holds the raw jnats `Connection`. All other services access it via `nats.underlying`
internally. No jnats types ever appear in user code.

## Wiring services together

Use `>+>` to accumulate services in scope. Each layer sees everything to its left:

```scala mdoc:silent
import zio.*
import zio.nats.*

val appLayer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStream.live >+>
  JetStreamManagement.live >+>
  KeyValueManagement.live
```

`>+>` is "provide and keep" — the output of each step is the union of its input and output.
This means a program requiring `Nats & JetStream & JetStreamManagement` can be satisfied by
`appLayer` without manually threading each service.

## Bucket services

`KeyValue.live(name)` and `ObjectStore.live(name)` are `ZLayer`s — they are the idiomatic way
to wire a named bucket into an application's dependency graph:

```scala mdoc:silent
import zio.*
import zio.nats.*

val kvLayer: ZLayer[Nats, NatsError, KeyValue] =
  KeyValue.live("my-bucket")

val osLayer: ZLayer[Nats, NatsError, ObjectStore] =
  ObjectStore.live("my-bucket")
```

`KeyValue.bucket(name)` and `ObjectStore.bucket(name)` are also available as `ZIO` effects for
programmatic use inside a for-comprehension when you don't need the bucket as a layer.

## NatsConfig

`NatsConfig` holds all connection parameters: server addresses, auth, TLS, timeouts, and
reconnect behaviour. It is always the entry point for constructing a `Nats` layer.

Two convenience constructors cover the most common cases:

```scala mdoc:silent
import zio.*
import zio.nats.*

// Equivalent: localhost:4222, no auth, no TLS
val layer1 = ZLayer.succeed(NatsConfig.default) >>> Nats.live
val layer2 = Nats.default
```

See [Configuration](../guides/06-configuration.md) for auth, TLS, environment variable loading,
and HOCON integration.

## Service descriptions

| Service | What it does | Guide |
|---|---|---|
| `Nats` | Core pub/sub, request-reply, connection lifecycle | [Pub/Sub](../guides/01-pubsub.md) |
| `JetStream` | Persistent publish + access to consumers | [JetStream](../guides/03-jetstream.md) |
| `JetStreamManagement` | Create/update/delete streams and consumers | [JetStream](../guides/03-jetstream.md) |
| `KeyValue` | Read/write/watch a named KV bucket | [Key-Value](../guides/04-key-value.md) |
| `KeyValueManagement` | Create/delete KV buckets | [Key-Value](../guides/04-key-value.md) |
| `ObjectStore` | Put/get/watch a named object store bucket | [Object Store](../guides/05-object-store.md) |
| `ObjectStoreManagement` | Create/delete object store buckets | [Object Store](../guides/05-object-store.md) |

## Next steps

- [Quick start](../quickstart.md) — wire `Nats` into a working app in 5 minutes
- [Pub/Sub guide](../guides/01-pubsub.md) — the core messaging operations
- [Configuration guide](../guides/06-configuration.md) — auth, TLS, environment variables
