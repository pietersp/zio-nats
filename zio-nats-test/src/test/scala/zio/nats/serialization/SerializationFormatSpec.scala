package zio.nats.serialization

import zio._
import zio.test._
import zio.test.Assertion._
import zio.blocks.schema._
import zio.Chunk

case class Person(name: String, age: Int)
object Person {
  implicit val schema: Schema[Person] = Schema.derived
}

object SerializationFormatSpec extends ZIOSpecDefault {
  def spec: Spec[Any, Throwable] = suite("SerializationFormat")(
    test("json format encodes and decodes") {
      val format = SerializationFormat.json
      val person = Person("Alice", 30)
      for {
        encoded <- ZIO.fromEither(format.encode(person))
        decoded <- ZIO.fromEither(format.decode[Person](encoded))
      } yield assert(decoded)(equalTo(person))
    }
  )
}