package zio.nats.serialization

import zio.*
import zio.blocks.schema.*
import zio.blocks.schema.json.JsonFormat
import zio.test.*
import zio.test.Assertion.*

case class Person(name: String, age: Int)
object Person {
  implicit val schema: Schema[Person] = Schema.derived
}

object SerializationFormatSpec extends ZIOSpecDefault {
  def spec: Spec[Any, Throwable] = suite("SerializationFormat")(
    test("json format encodes and decodes") {
      val person = Person("Alice", 30)
      for {
        encoded <- ZIO.fromEither(NatsSerializer.encode(person, JsonFormat))
        decoded <- ZIO.fromEither(NatsSerializer.decode[Person](encoded, JsonFormat))
      } yield assertTrue(decoded == person)
    }
  )
}
