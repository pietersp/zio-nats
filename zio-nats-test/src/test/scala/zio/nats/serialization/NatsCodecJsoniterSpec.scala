package zio.nats.serialization

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import zio.nats.*
import zio.nats.given
import zio.test.*
import zio.test.Assertion.*

case class Widget(label: String, count: Int)
object Widget {
  given JsonValueCodec[Widget] = JsonCodecMaker.make
}

object NatsCodecJsoniterSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("NatsCodecJsoniter")(
    test("encodes and decodes a case class via implicit bridge") {
      val widget  = Widget("sprocket", 42)
      val encoded = NatsCodec[Widget].encode(widget)
      val decoded = NatsCodec[Widget].decode(encoded)
      assertTrue(decoded == Right(widget))
    },

    test("decode returns NatsDecodeError on invalid input") {
      val garbled = zio.Chunk.fromArray("not valid json{{".getBytes)
      val result  = NatsCodec[Widget].decode(garbled)
      assertTrue(result.isLeft)
    },

    test("fromJsoniter wraps an explicit codec") {
      val widget  = Widget("cog", 7)
      val codec   = NatsCodecJsoniter.fromJsoniter(JsonCodecMaker.make[Widget])
      val encoded = codec.encode(widget)
      val decoded = codec.decode(encoded)
      assertTrue(decoded == Right(widget))
    }
  )
}
