---
id: construction
title: Layer Construction
---

Every zio-nats feature is a `ZLayer`. Knowing the dependency tree tells you which layers to pass to `.provide` and what each one requires - this page is a reference for wiring any combination of services together.

## Dependency tree

All services depend, directly or indirectly, on `Nats`. `NatsConfig` is the single entry point:

```
NatsConfig
    └── Nats.live
            ├── JetStream.live
            ├── JetStreamManagement.live
            ├── KeyValue.live(name)
            ├── KeyValueManagement.live
            ├── ObjectStore.live(name)
            └── ObjectStoreManagement.live
```

`NatsService` is the exception - it is not a `ZLayer`. It is obtained via `nats.service(config, endpoints)`, a scoped `ZIO` effect whose lifetime is tied to the enclosing `Scope`. See [Service Framework](../guides/04-service.md).

## Automatic construction

ZIO's `.provide` resolves the dependency tree automatically. List the layers your program needs and ZIO wires them together in the right order - you do not need to express the relationships between them manually:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

def program: ZIO[Nats & JetStream & JetStreamManagement, NatsError, Unit] =
  ZIO.unit

val _ = program.provide(
  Nats.default,
  JetStream.live,
  JetStreamManagement.live
)
```

This is the recommended form for most applications. The section below covers when you need the layer as a value instead.

## Manual composition

When you need the composed layer as a value - most commonly with `.provideShared` in test suites to share a single container across all tests - use `>+>`. Each step receives the full union of everything to its left as its input:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val appLayer =
  ZLayer.succeed(NatsConfig.default) >>>
  Nats.live >+>
  JetStream.live >+>
  JetStreamManagement.live
```

`>+>` is "provide and keep". The output of `appLayer` above is `Nats & JetStream & JetStreamManagement` and can be passed directly to `.provideShared`.

## `Nats` construction options

Three ways to provide the root `Nats` layer, in order of convenience:

| Form                                          | Use when                                           |
|-----------------------------------------------|----------------------------------------------------|
| `Nats.default`                                | Local development; `localhost:4222`, no auth       |
| `ZLayer.succeed(NatsConfig(...)) >>> Nats.live` | Config constructed in code                        |
| `NatsConfig.fromConfig >>> Nats.live`         | Config loaded from environment variables or HOCON  |

See [Configuration](../guides/08-configuration.md) for auth, TLS, timeouts, and the full field list.

## Named bucket services

`KeyValue` and `ObjectStore` each have two construction forms depending on how the bucket fits into the program:

| Form                          | Type                                 | Use when                                       |
|-------------------------------|--------------------------------------|------------------------------------------------|
| `KeyValue.live("name")`       | `ZLayer[Nats, NatsError, KeyValue]`  | Bucket is a fixed service in the application   |
| `KeyValue.bucket("name")`     | `ZIO[Nats, NatsError, KeyValue]`     | Bucket is acquired inside a for-comprehension  |

The same pattern applies to `ObjectStore.live("name")` and `ObjectStore.bucket("name")`.

## Next steps

- [Configuration guide](../guides/08-configuration.md) - auth, TLS, and environment variable loading
- [Testing guide](../guides/09-testing.md) - `provideShared` and testkit layers
