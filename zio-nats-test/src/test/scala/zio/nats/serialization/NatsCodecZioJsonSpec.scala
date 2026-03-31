package zio.nats.serialization

import zio.json.{DeriveJsonCodec, DeriveJsonDecoder, DeriveJsonEncoder, JsonCodec, JsonDecoder, JsonEncoder}
import zio.nats.*
import zio.nats.given
import zio.test.*
import zio.test.Assertion.*

case class Gizmo(label: String, quantity: Int)
object Gizmo {
  given JsonEncoder[Gizmo] = DeriveJsonEncoder.gen[Gizmo]
  given JsonDecoder[Gizmo] = DeriveJsonDecoder.gen[Gizmo]
}

case class Doohickey(name: String, active: Boolean)
object Doohickey {
  given JsonCodec[Doohickey] = DeriveJsonCodec.gen[Doohickey]
}

object NatsCodecZioJsonSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("NatsCodecZioJson")(
    test("encodes and decodes a case class via implicit bridge (separate encoder/decoder)") {
      val gizmo   = Gizmo("sprocket", 42)
      val encoded = NatsCodec[Gizmo].encode(gizmo)
      val decoded = NatsCodec[Gizmo].decode(encoded)
      assertTrue(decoded == Right(gizmo))
    },

    test("encodes and decodes a case class via implicit bridge (combined JsonCodec)") {
      val doohickey = Doohickey("widget", active = true)
      val encoded   = NatsCodec[Doohickey].encode(doohickey)
      val decoded   = NatsCodec[Doohickey].decode(encoded)
      assertTrue(decoded == Right(doohickey))
    },

    test("decode returns NatsDecodeError on invalid json") {
      val garbled = zio.Chunk.fromArray("not valid json{{".getBytes("UTF-8"))
      val result  = NatsCodec[Gizmo].decode(garbled)
      assertTrue(result.isLeft)
    },

    test("decode returns NatsDecodeError on wrong structure") {
      val wrongJson = zio.Chunk.fromArray("""{"x":1}""".getBytes("UTF-8"))
      val result    = NatsCodec[Gizmo].decode(wrongJson)
      assertTrue(result.isLeft)
    },

    test("fromZioJson wraps an explicit encoder/decoder pair") {
      val gizmo   = Gizmo("cog", 7)
      val codec   = NatsCodecZioJson.fromZioJson(DeriveJsonEncoder.gen[Gizmo], DeriveJsonDecoder.gen[Gizmo])
      val encoded = codec.encode(gizmo)
      val decoded = codec.decode(encoded)
      assertTrue(decoded == Right(gizmo))
    }
  )
}
