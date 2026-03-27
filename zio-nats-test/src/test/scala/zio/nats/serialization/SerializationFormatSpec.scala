package zio.nats.serialization

import zio.*
import zio.blocks.schema.*
import zio.blocks.schema.json.JsonFormat
import zio.nats.*
import zio.test.*
import zio.test.Assertion.*

case class Person(name: String, age: Int)
object Person {
  given schema: Schema[Person] = Schema.derived
}

object SerializationFormatSpec extends ZIOSpecDefault {

  // Derive a NatsCodec for all Schema types using JsonFormat
  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  def spec: Spec[Any, Throwable] = suite("NatsCodec / SerializationFormat")(
    test("json format encodes and decodes a case class") {
      val person  = Person("Alice", 30)
      val encoded = NatsCodec[Person].encode(person)
      val decoded = NatsCodec[Person].decode(encoded)
      assertTrue(decoded == Right(person))
    },

    test("decode returns NatsDecodeError on invalid input") {
      val garbled = zio.Chunk.fromArray("not-valid-json{{".getBytes)
      val result  = NatsCodec[Person].decode(garbled)
      assertTrue(result.isLeft)
    }
  )
}
