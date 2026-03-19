package zio.nats.serialization

import play.api.libs.json.{Format, Json}
import zio.nats.*
import zio.nats.given
import zio.test.*
import zio.test.Assertion.*

case class Gadget(name: String, price: Double)
object Gadget {
  given Format[Gadget] = Json.format[Gadget]
}

object NatsCodecPlayJsonSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("NatsCodecPlayJson")(
    test("encodes and decodes a case class via implicit bridge") {
      val gadget  = Gadget("widget", 9.99)
      val encoded = NatsCodec[Gadget].encode(gadget)
      val decoded = NatsCodec[Gadget].decode(encoded)
      assertTrue(decoded == Right(gadget))
    },

    test("decode returns NatsDecodeError on invalid json") {
      val garbled = zio.Chunk.fromArray("not valid json{{".getBytes("UTF-8"))
      val result  = NatsCodec[Gadget].decode(garbled)
      assertTrue(result.isLeft)
    },

    test("decode returns NatsDecodeError on wrong structure") {
      val wrongJson = zio.Chunk.fromArray("""{"x":1}""".getBytes("UTF-8"))
      val result    = NatsCodec[Gadget].decode(wrongJson)
      assertTrue(result.isLeft)
    },

    test("fromPlayJson wraps an explicit codec") {
      val gadget  = Gadget("sprocket", 3.14)
      val codec   = NatsCodec.fromPlayJson(Json.format[Gadget])
      val encoded = codec.encode(gadget)
      val decoded = codec.decode(encoded)
      assertTrue(decoded == Right(gadget))
    }
  )
}
