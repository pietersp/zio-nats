package zio.nats

import zio.Chunk
import zio.test.*

object CoreTypesSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Core Types")(
    suite("Subject.parse")(
      test("returns Left for null") {
        assertTrue(Subject.parse(null).isLeft)
      },
      test("returns Left for blank string") {
        assertTrue(Subject.parse("   ").isLeft)
      },
      test("returns Right for a valid subject") {
        assertTrue(Subject.parse("my.topic") == Right(Subject("my.topic")))
      }
    ),

    suite("Headers")(
      test("add appends multiple values for the same key") {
        val h = Headers("X-Key" -> "v1").add("X-Key", "v2")
        assertTrue(h.get("X-Key") == Chunk("v1", "v2"))
      },
      test("++ merges headers combining values for duplicate keys") {
        val h1 = Headers("X-Key" -> "a")
        val h2 = Headers("X-Key" -> "b")
        assertTrue((h1 ++ h2).get("X-Key") == Chunk("a", "b"))
      }
    ),

    suite("ObjectMeta.toJava")(
      test("includes description and headers when set") {
        val meta  = ObjectMeta(
          name = "test-obj",
          description = Some("desc"),
          headers = Headers("X-Tag" -> "val")
        )
        val jMeta = meta.toJava
        assertTrue(
          jMeta.getObjectName == "test-obj",
          jMeta.getDescription == "desc",
          jMeta.getHeaders != null
        )
      }
    )
  )
}
