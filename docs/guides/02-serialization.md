---
id: serialization
title: Serialization
---

All `publish`, `subscribe`, and `request` methods are generic over `[A: NatsCodec]`. The codec is resolved at compile time - no casting, no runtime lookup, and no separate encode/decode step in your code.

## Built-in codecs

Two codecs are always available with no setup - they cover the cases where encoding is handled externally or the payload is already in wire form:

| Type           | Behaviour                        |
|----------------|----------------------------------|
| `String`       | UTF-8 encode/decode              |
| `Chunk[Byte]`  | Identity - bytes pass through unchanged |

Use them to publish raw events or forward payloads without touching the content:

```scala mdoc:silent
import zio.*
import zio.nats.*

val builtins: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("shop.events"), "order-placed")
      _ <- nats.publish(Subject("shop.events"), Chunk.fromArray("order-placed".getBytes))
    } yield ()
  }
```

For domain types like `OrderPlaced` or `PriceUpdate`, read on.

## zio-blocks

[zio-blocks](https://zio.dev/zio-blocks) derives codecs from a compile-time `Schema`. It is included in the batteries-included `zio-nats` artifact and is the recommended approach for domain types - define the schema once and every format (JSON, Avro, MsgPack) becomes available with no extra code per type.

### Setup

Derive a `Schema` for your type and build a codec from a format. We use `OrderPlaced` as the domain type throughout this section:

```scala mdoc:silent
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

case class OrderPlaced(orderId: String, customerId: String, total: Double)
object OrderPlaced {
  given Schema[OrderPlaced] = Schema.derived
}

val codecs = NatsCodec.fromFormat(JsonFormat)
import codecs.derived  // NatsCodec[OrderPlaced] is now in implicit scope
```

What's happening:

1. `Schema.derived` - zio-blocks derives a schema for `OrderPlaced` at compile time.
2. `NatsCodec.fromFormat(JsonFormat)` - creates a `Builder` that can derive `NatsCodec[A]` for any `A` with a `Schema[A]` in scope.
3. `import codecs.derived` - this is where the codec for `OrderPlaced` is compiled and cached. If the `Schema` is missing or the format cannot handle the type, you get an error here - not buried in a later `publish` call.

### Publish and subscribe

With the codec in scope, `Nats#publish` and `Nats#subscribe` accept `OrderPlaced` directly:

```scala mdoc:silent
import zio.*
import zio.nats.*

val typedPubSub: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("shop.orders"), OrderPlaced("ord-1", "cust-42", 59.99))
      _ <- nats.subscribe[OrderPlaced](Subject("shop.orders"))
             .map(_.value)
             .tap(o => ZIO.debug(s"Order ${o.orderId} from ${o.customerId}: £${o.total}"))
             .runDrain
    } yield ()
  }
```

### Renaming fields

The `@Modifier.rename` annotation overrides the serialized key name for a specific field at the schema level, so it applies across all formats (JSON, Avro, MsgPack) without any codec-specific configuration. This is useful when publishing events to an external system that expects snake_case field names - a shipping carrier's webhook, a payment gateway, or a third-party analytics platform - while keeping your Scala field names in camelCase.

Annotate the fields that need a different wire name:

```scala mdoc:silent
import zio.blocks.schema.{Schema, Modifier}

case class ShipmentEvent(
  @Modifier.rename("order_id")     orderId: String,
  @Modifier.rename("carrier_code") carrierCode: String,
  @Modifier.rename("tracking_no")  trackingNo: String
)
object ShipmentEvent {
  given Schema[ShipmentEvent] = Schema.derived
}
```

Since `import codecs.derived` is already in scope from the setup above, `NatsCodec[ShipmentEvent]` is derived automatically. We can confirm the wire format:

```scala mdoc
val encoded = NatsCodec[ShipmentEvent].encode(
  ShipmentEvent("ord-1", "DHL", "Z123")
)
new String(encoded.toArray)
```

The JSON keys are snake_case; the Scala fields remain camelCase. Subscribers with the same schema decode the snake_case JSON back into the camelCase Scala type automatically.

### Available formats

`zio-nats` (batteries-included) brings in JSON transitively. For other formats, add the corresponding artifact:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-schema-avro"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-msgpack" % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-thrift"  % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-bson"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-toon"    % "<zio-blocks-version>"
libraryDependencies += "dev.zio" %% "zio-blocks-schema-xml"     % "<zio-blocks-version>"
```

Each format follows the same pattern - swap out the import and pass the format object to `NatsCodec.fromFormat`. For example, XML uses `import zio.blocks.schema.xml.XmlFormat` in place of `import zio.blocks.schema.json.JsonFormat`. See the [zio-blocks reference](https://zio.dev/zio-blocks/reference/xml) for format-specific configuration.

:::tip
If you do not want the zio-blocks dependency, replace `zio-nats` with `zio-nats-core` in your `build.sbt`. The built-in `String` and `Chunk[Byte]` codecs are still available; you provide the rest. See [Custom codecs](#custom-codecs) below.
:::

## jsoniter-scala

[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) is a high-performance JSON library that generates codecs at compile time with minimal overhead. Use `zio-nats-jsoniter` when a subject carries high-frequency messages - such as live price updates - where every microsecond of serialization overhead matters, or when you are already using jsoniter elsewhere and want to reuse existing codecs.

Add to `build.sbt`:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-jsoniter" % "@VERSION@"
```

### Automatic bridging

Place a `given JsonValueCodec[A]` in scope and import with `import zio.nats.{given, *}`. The library bridges it to `NatsCodec[A]` automatically - no builder step required:

```scala mdoc:reset silent
import zio.*
import zio.nats.{given, *}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class PriceUpdate(itemId: String, price: Double)
object PriceUpdate {
  given JsonValueCodec[PriceUpdate] = JsonCodecMaker.make
}

val priceUpdates: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("shop.pricing"), PriceUpdate("item-456", 12.99))
      _ <- nats.subscribe[PriceUpdate](Subject("shop.pricing")).map(_.value).runDrain
    } yield ()
  }
```

A `NotGiven[NatsCodec[A]]` guard ensures the bridge never shadows built-in codecs or any explicit `given NatsCodec[A]` already in scope.

### Explicit one-off codec

Use `NatsCodecJsoniter.fromJsoniter` when you need a codec for a single call without placing it in implicit scope:

```scala mdoc:silent
import zio.nats.*
import zio.nats.NatsCodecJsoniter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

val priceUpdateCodec: NatsCodec[PriceUpdate] =
  NatsCodecJsoniter.fromJsoniter(JsonCodecMaker.make[PriceUpdate])
```

## play-json

[play-json](https://github.com/playframework/play-json) works the same way as jsoniter - place a `given Format[A]` in scope and it is bridged to `NatsCodec[A]` automatically. This is useful when your project already defines play-json `Format` instances and you want to reuse them without any additional codec setup.

Add to `build.sbt`:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-play-json" % "@VERSION@"
```

Place the `Format` in scope and import with `import zio.nats.{given, *}`:

```scala mdoc:reset silent
import zio.*
import zio.nats.{given, *}
import play.api.libs.json.{Format, Json}

case class ShipmentStatus(orderId: String, status: String)
object ShipmentStatus {
  given Format[ShipmentStatus] = Json.format[ShipmentStatus]
}

val shipmentEvents: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    nats.publish(Subject("shop.shipments"), ShipmentStatus("ord-1", "dispatched"))
  }
```

Use `NatsCodecPlayJson.fromPlayJson(format)` for an explicit one-off codec.

## Mixing codecs

All three integration styles can coexist in the same project. Each type resolves its codec independently - zio-blocks for most types, jsoniter for the ones that need maximum throughput:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

// Most domain types use zio-blocks
case class OrderConfirmed(orderId: String, ts: Long)
object OrderConfirmed {
  given Schema[OrderConfirmed] = Schema.derived
}

val defaultCodecs = NatsCodec.fromFormat(JsonFormat)
import defaultCodecs.derived

// High-frequency type uses jsoniter for performance
case class InventoryTick(itemId: String, delta: Int)
object InventoryTick {
  given JsonValueCodec[InventoryTick] = JsonCodecMaker.make
}
// NatsCodec[InventoryTick] resolved via the jsoniter bridge
// NatsCodec[OrderConfirmed] resolved via zio-blocks
```

## Custom codecs

`NatsCodec[A]` is a two-method trait that lives in `zio-nats-core` with no external dependencies. Implementing it directly is the right choice when you are heavily invested in a serialization library not covered by the built-in integrations - circe, µPickle, Protocol Buffers, a proprietary binary format - and want to bridge it without adopting zio-blocks. It is also the right choice when you want the smallest possible dependency footprint.

In either case, **replace** `zio-nats` with `zio-nats-core` in `build.sbt`. The two artifacts are mutually exclusive - `zio-nats-core` has no zio-blocks dependency, while `zio-nats` (batteries-included) brings it in transitively:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-core" % "@VERSION@"
```

The two methods to implement are `encode`, which produces bytes, and `decode`, which recovers the value or returns a `NatsDecodeError`. To show how compact this can be, here is a binary protocol for high-frequency stock ticks - each message is exactly 10 bytes:

```scala mdoc:silent
import java.nio.ByteBuffer
import zio.Chunk
import zio.nats.*

case class StockTick(itemId: Int, priceCents: Int, quantity: Short)

given NatsCodec[StockTick] = new NatsCodec[StockTick] {
  private val Size = 10 // 4 + 4 + 2 bytes

  def encode(t: StockTick): Chunk[Byte] = {
    val buf = ByteBuffer.allocate(Size)
    buf.putInt(t.itemId)
    buf.putInt(t.priceCents)
    buf.putShort(t.quantity)
    Chunk.fromArray(buf.array())
  }

  def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, StockTick] =
    if (bytes.length != Size)
      Left(NatsDecodeError(s"Expected $Size bytes, got ${bytes.length}"))
    else {
      val buf = ByteBuffer.wrap(bytes.toArray)
      Right(StockTick(buf.getInt(), buf.getInt(), buf.getShort()))
    }
}
```

We can verify the payload size stays at exactly 10 bytes regardless of the values:

```scala mdoc
NatsCodec[StockTick].encode(StockTick(42, 1299, 100)).length
```

With the `given` in scope, `StockTick` works exactly like any other type:

```scala mdoc:compile-only
import java.nio.ByteBuffer
import zio.*
import zio.Chunk
import zio.nats.*

case class StockTick(itemId: Int, priceCents: Int, quantity: Short)

given NatsCodec[StockTick] = new NatsCodec[StockTick] {
  private val Size = 10
  def encode(t: StockTick): Chunk[Byte] = {
    val buf = ByteBuffer.allocate(Size)
    buf.putInt(t.itemId)
    buf.putInt(t.priceCents)
    buf.putShort(t.quantity)
    Chunk.fromArray(buf.array())
  }
  def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, StockTick] =
    if (bytes.length != Size) Left(NatsDecodeError(s"Expected $Size bytes, got ${bytes.length}"))
    else { val buf = ByteBuffer.wrap(bytes.toArray); Right(StockTick(buf.getInt(), buf.getInt(), buf.getShort())) }
}

val ticks: ZIO[Nats, NatsError, Unit] =
  ZIO.serviceWithZIO[Nats] { nats =>
    for {
      _ <- nats.publish(Subject("shop.ticks"), StockTick(42, 1299, 100))
      _ <- nats.subscribe[StockTick](Subject("shop.ticks")).map(_.value).runDrain
    } yield ()
  }
```

## Next steps

- [Pub/Sub guide](./01-pubsub.md) - the underlying publish/subscribe mechanics
- [JetStream guide](./03-jetstream.md) - persistent messaging with typed payloads
- [Modules reference](../reference/03-modules.md) - which artifact to add for each integration
