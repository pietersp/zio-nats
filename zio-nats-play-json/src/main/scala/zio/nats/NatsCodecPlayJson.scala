package zio.nats

import play.api.libs.json.{Format, JsError, JsSuccess, Json}
import zio.Chunk

/**
 * Automatically bridge any `Format[A]` in implicit scope to [[NatsCodec]][A].
 *
 * This is a top-level given in `package zio.nats`, so it is brought into scope
 * by `import zio.nats.*` when `zio-nats-play-json` is on the classpath —
 * no additional import or builder step required.
 *
 * The `NotGiven[NatsCodec[A]]` guard ensures this given does not shadow
 * built-in codecs (e.g. `NatsCodec[String]`, `NatsCodec[Chunk[Byte]]`) or
 * any explicit `given NatsCodec[A]` already in scope.
 *
 * {{{
 * given Format[Person] = Json.format[Person]
 *
 * // NatsCodec[Person] resolved automatically via import zio.nats.*:
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 */
given fromPlayJsonFormat[A](using fmt: Format[A], ev: scala.util.NotGiven[NatsCodec[A]]): NatsCodec[A] =
  NatsCodecPlayJson.wrap(fmt)

/**
 * play-json integration for [[NatsCodec]].
 *
 * Bridges `Format[A]` (play-json's combined read/write codec) to the
 * library's [[NatsCodec]] typeclass. The bridge `given` [[fromPlayJsonFormat]]
 * is the primary integration point: after `import zio.nats.*`, any
 * `Format[A]` that is in implicit scope is automatically promoted to a
 * `NatsCodec[A]`.
 *
 * JSON is serialised as UTF-8 bytes.
 *
 * ==Typical usage==
 *
 * {{{
 * import play.api.libs.json.{Format, Json}
 *
 * case class Person(name: String, age: Int)
 * object Person {
 *   given Format[Person] = Json.format[Person]
 * }
 *
 * // NatsCodec[Person] is now available via fromPlayJsonFormat
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 *
 * For an explicit one-off codec, use the [[NatsCodec.fromPlayJson]] extension
 * method:
 *
 * {{{
 * val codec: NatsCodec[Person] = NatsCodec.fromPlayJson(Json.format[Person])
 * }}}
 */
object NatsCodecPlayJson {

  /**
   * Wrap a play-json `Format[A]` as a [[NatsCodec]][A].
   *
   * Encoding delegates to `Json.stringify`; decoding delegates to
   * `Json.parse` + `validate[A]`, mapping any `JsError` or thrown exception
   * to [[NatsDecodeError]].
   *
   * @param format
   *   A play-json `Format[A]`.
   */
  def wrap[A](format: Format[A]): NatsCodec[A] = new NatsCodec[A] {
    def encode(value: A): Chunk[Byte] =
      Chunk.fromArray(Json.stringify(Json.toJson(value)(format)).getBytes("UTF-8"))

    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
      try
        Json.parse(bytes.toArray).validate[A](format) match {
          case JsSuccess(value, _) => Right(value)
          case JsError(errors)     =>
            Left(NatsDecodeError(errors.map { case (path, errs) => s"$path: ${errs.map(_.message).mkString(", ")}" }.mkString("; ")))
        }
      catch {
        case e: Exception => Left(NatsDecodeError(e.getMessage))
      }
  }

}