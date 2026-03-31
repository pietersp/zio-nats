package zio.nats

import zio.Chunk
import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Automatically bridge any `JsonEncoder[A]` + `JsonDecoder[A]` pair in implicit
 * scope to [[NatsCodec]][A].
 *
 * This is a top-level given in `package zio.nats`. In Scala 3, package-level
 * given instances are not imported by a plain wildcard `import zio.nats.*`. Use
 * `import zio.nats.{given, *}` (or `import zio.nats.given`) to bring this given
 * into scope.
 *
 * The `NotGiven[NatsCodec[A]]` guard ensures this given does not shadow
 * built-in codecs (e.g. `NatsCodec[String]`, `NatsCodec[Chunk[Byte]]`) or any
 * explicit `given NatsCodec[A]` already in scope.
 *
 * Both a separate `JsonEncoder[A]` and `JsonDecoder[A]` or a combined
 * `JsonCodec[A]` (which extends both) satisfy the implicit requirements.
 *
 * {{{
 * import zio.nats.{given, *}
 * import zio.json.{JsonEncoder, JsonDecoder, DeriveJsonEncoder, DeriveJsonDecoder}
 *
 * case class Person(name: String, age: Int)
 * object Person {
 *   given JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
 *   given JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]
 * }
 *
 * // NatsCodec[Person] resolved automatically via fromZioJson:
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 */
given fromZioJson[A](using
  enc: JsonEncoder[A],
  dec: JsonDecoder[A],
  ev: scala.util.NotGiven[NatsCodec[A]]
): NatsCodec[A] =
  NatsCodecZioJson.wrap(enc, dec)

/**
 * zio-json integration for [[NatsCodec]].
 *
 * Bridges `JsonEncoder[A]` and `JsonDecoder[A]` (zio-json's typeclasses) to the
 * library's [[NatsCodec]] typeclass. The bridge `given fromZioJson` is the
 * primary integration point: after `import zio.nats.{given, *}`, any
 * `JsonEncoder[A]` + `JsonDecoder[A]` pair that is in implicit scope is
 * automatically promoted to a `NatsCodec[A]`. A combined `JsonCodec[A]` (which
 * extends both) works equally well.
 *
 * JSON is serialised as UTF-8 bytes.
 *
 * ==Typical usage==
 *
 * {{{
 * import zio.json.{DeriveJsonEncoder, DeriveJsonDecoder, JsonEncoder, JsonDecoder}
 *
 * case class Person(name: String, age: Int)
 * object Person {
 *   given JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
 *   given JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]
 * }
 *
 * // NatsCodec[Person] is now available via the fromZioJson given
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 *
 * Or with a combined `JsonCodec[A]`:
 *
 * {{{
 * import zio.json.{DeriveJsonCodec, JsonCodec}
 *
 * case class Person(name: String, age: Int)
 * object Person {
 *   given JsonCodec[Person] = DeriveJsonCodec.gen[Person]
 * }
 * }}}
 *
 * For an explicit one-off codec, use [[zio.nats.NatsCodecZioJson.fromZioJson]]:
 *
 * {{{
 * import zio.json.{DeriveJsonEncoder, DeriveJsonDecoder}
 *
 * val codec: NatsCodec[Person] = NatsCodecZioJson.fromZioJson(
 *   DeriveJsonEncoder.gen[Person],
 *   DeriveJsonDecoder.gen[Person]
 * )
 * }}}
 */
object NatsCodecZioJson {

  /**
   * Wrap a zio-json `JsonEncoder[A]` + `JsonDecoder[A]` pair as a
   * [[NatsCodec]][A].
   *
   * Encoding delegates to `JsonEncoder.encodeJson` (no indentation); decoding
   * delegates to `JsonDecoder.decodeJson`, mapping any `Left` error string to
   * [[NatsDecodeError]].
   *
   * @param encoder
   *   A zio-json `JsonEncoder[A]`.
   * @param decoder
   *   A zio-json `JsonDecoder[A]`.
   */
  def wrap[A](encoder: JsonEncoder[A], decoder: JsonDecoder[A]): NatsCodec[A] = new NatsCodec[A] {
    def encode(value: A): Chunk[Byte] =
      Chunk.fromArray(encoder.encodeJson(value, None).toString.getBytes("UTF-8"))

    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
      decoder
        .decodeJson(new String(bytes.toArray, "UTF-8"))
        .left
        .map(NatsDecodeError(_))
  }

  /**
   * Wrap a zio-json `JsonEncoder[A]` + `JsonDecoder[A]` pair as a
   * [[NatsCodec]][A].
   *
   * Use this for an explicit one-off codec. For automatic bridging via implicit
   * resolution, ensure a `given JsonEncoder[A]` and `given JsonDecoder[A]` are
   * in scope (or a combined `given JsonCodec[A]`) and use
   * `import zio.nats.{given, *}` — the `fromZioJson` given handles the rest.
   *
   * @param encoder
   *   A zio-json `JsonEncoder[A]`.
   * @param decoder
   *   A zio-json `JsonDecoder[A]`.
   */
  def fromZioJson[A](encoder: JsonEncoder[A], decoder: JsonDecoder[A]): NatsCodec[A] = wrap(encoder, decoder)

}
