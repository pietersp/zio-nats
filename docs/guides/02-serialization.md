---
id: serialization
title: Serialization
---

# Serialization

> Publish and subscribe using domain types — not raw bytes or strings.

All `publish`, `subscribe`, and `request` methods are generic over `[A: NatsCodec]`. The codec
is resolved at compile time, so there is no casting or runtime lookup.

## Prerequisites

- [Quick start](../quickstart.md) completed

## Built-in codecs

Two codecs are always available with no setup:

| Type | Behaviour |
|---|---|
| `String` | UTF-8 encode/decode |
| `Chunk[Byte]` | Identity — bytes pass through unchanged |

```scala mdoc:silent
import zio.*
import zio.nats.*

val builtins: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("text"), "hello")                          // NatsCodec[String]
      _ <- nats.publish(Subject("raw"),  Chunk.fromArray("hi".getBytes))   // NatsCodec[Chunk[Byte]]
    } yield ()
  }
```

These cover the cases where you want to handle encoding yourself, or where the payload is
already a string. For domain types, read on.

## zio-blocks (recommended for schema-driven types)

[zio-blocks](https://zio.dev/zio-blocks) derives codecs from schemas. It is included in the
batteries-included `zio-nats` artifact.

### Setup

Define a `Schema` for your type and build a codec from a format:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class Person(name: String, age: Int)
object Person {
  given schema: Schema[Person] = Schema.derived
}

// Build a codec from the JSON format. The Schema[Person] is resolved here,
// at the import site — not on the first publish call.
val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived   // NatsCodec[Person] is now in implicit scope
```

**What's happening:**

1. `Schema.derived` — zio-blocks macro derives a schema for `Person` at compile time.
2. `NatsCodec.fromFormat(JsonFormat)` — creates a `Builder` that can derive `NatsCodec[A]` for any `A` with a `Schema[A]` in scope.
3. `import codecs.derived` — this is where the codec for `Person` is compiled and cached. If `Person` had no `Schema`, or the format could not handle it, you would get an error here — not buried inside a later `publish` call.

### Publish and subscribe

```scala mdoc:silent
import zio.*
import zio.nats.*

val typedPubSub: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("people"), Person("Alice", 30))

      _ <- nats.subscribe[Person](Subject("people"))
             .map(_.value)
             .tap(p => ZIO.debug(s"Got: ${p.name}, ${p.age}"))
             .runDrain
    } yield ()
  }
```

## Per-type codec override

Multiple formats can coexist. Override for a specific type with a plain `given`:

```scala mdoc:silent
import zio.nats.*
import zio.blocks.schema.json.JsonFormat

// AuditEvent uses a different format than the default
given auditCodec: NatsCodec[Person] =
  NatsCodec.fromFormat(JsonFormat).derived[Person]
```

The explicit `given` always wins over the builder's derived instance.

## Available zio-blocks formats

`zio-nats` (batteries-included) brings in `zio-blocks-schema` (JSON) transitively. For other
formats, add the corresponding artifact:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"     % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack"  % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"   % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"     % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"     % "<zio-blocks-version>"
```

## jsoniter-scala

[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) is a high-performance JSON
library. Use `zio-nats-jsoniter` as an alternative or complement to zio-blocks.

Add to `build.sbt` (pair with `zio-nats-core` or alongside `zio-nats`):

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "@VERSION@"
```

### Automatic bridging

Place a `given JsonValueCodec[A]` in scope and use `import zio.nats.{given, *}`. The library bridges it to
`NatsCodec[A]` automatically — no builder step required:

```scala mdoc:reset silent
import zio.*
import zio.nats.{given, *}   // {given, *} required for the fromJsonValueCodec bridge
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class Event(kind: String, ts: Long)
object Event {
  given JsonValueCodec[Event] = JsonCodecMaker.make
}

// NatsCodec[Event] is resolved automatically via the given fromJsonValueCodec bridge
val jsoniterExample: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("events"), Event("click", 1234567890L))
      _ <- nats.subscribe[Event](Subject("events")).map(_.value).runDrain
    } yield ()
  }
```

A `NotGiven[NatsCodec[A]]` guard ensures the bridge never shadows built-in codecs (`String`,
`Chunk[Byte]`) or any explicit `given NatsCodec[A]` you define.

### Explicit one-off codec

Use `NatsCodecJsoniter.fromJsoniter` when you need a codec without adding it to implicit scope:

```scala mdoc:silent
import zio.nats.*
import zio.nats.NatsCodecJsoniter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

val eventCodec: NatsCodec[Event] =
  NatsCodecJsoniter.fromJsoniter(JsonCodecMaker.make[Event])
```

## play-json

[play-json](https://github.com/playframework/play-json) works the same way as jsoniter.
Add `zio-nats-play-json` and place a `given Format[A]` in scope with `import zio.nats.{given, *}`:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "@VERSION@"
```

```scala mdoc:reset silent
import zio.*
import zio.nats.{given, *}   // {given, *} required for the fromPlayJsonFormat bridge
import play.api.libs.json.{Format, Json}

case class Order(id: String, total: Double)
object Order {
  given Format[Order] = Json.format[Order]
}

// NatsCodec[Order] resolved automatically via given fromPlayJsonFormat
val playJsonExample: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.publish(Subject("orders"), Order("order-1", 99.99))
  }
```

Use `NatsCodecPlayJson.fromPlayJson(format)` for an explicit one-off codec.

## Mixing codecs

All three integration styles can coexist in the same project:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

// Most types use zio-blocks
case class UserCreated(userId: String)
object UserCreated:
  given Schema[UserCreated] = Schema.derived

val defaultCodecs = NatsCodec.fromFormat(JsonFormat)
import defaultCodecs.derived

// One type uses jsoniter for performance
case class HighFreqEvent(seq: Long, value: Double)
object HighFreqEvent:
  given JsonValueCodec[HighFreqEvent] = JsonCodecMaker.make
// NatsCodec[HighFreqEvent] resolved via the jsoniter bridge; others use zio-blocks
```

## Next steps

- [Pub/Sub guide](./01-pubsub.md) — the underlying publish/subscribe mechanics
- [JetStream guide](./03-jetstream.md) — persistent messaging with typed payloads
- [Modules reference](../reference/03-modules.md) — which artifact to add for each integration
