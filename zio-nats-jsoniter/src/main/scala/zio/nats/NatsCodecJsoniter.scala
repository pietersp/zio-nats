package zio.nats

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import zio.Chunk

import scala.util.Try

/**
 * Automatically bridge any `JsonValueCodec[A]` in implicit scope to
 * [[NatsCodec]][A].
 *
 * This is a top-level given in `package zio.nats`, so it is brought into scope
 * by `import zio.nats.*` when `zio-nats-jsoniter` is on the classpath — no
 * additional import or builder step required.
 *
 * The `NotGiven[NatsCodec[A]]` guard ensures this given does not shadow
 * built-in codecs (e.g. `NatsCodec[String]`, `NatsCodec[Chunk[Byte]]`) or any
 * explicit `given NatsCodec[A]` already in scope.
 *
 * {{{
 * given JsonValueCodec[Person] = JsonCodecMaker.make
 *
 * // NatsCodec[Person] resolved automatically via import zio.nats.*:
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 */
given fromJsonValueCodec[A](using jc: JsonValueCodec[A], ev: scala.util.NotGiven[NatsCodec[A]]): NatsCodec[A] =
  NatsCodecJsoniter.wrap(jc)

/**
 * jsoniter-scala integration for [[NatsCodec]].
 *
 * Bridges `JsonValueCodec[A]` (jsoniter-scala's codec type) to the library's
 * [[NatsCodec]] typeclass. The bridge `given` [[fromJsonValueCodec]] is the
 * primary integration point: after `import zio.nats.*`, any `JsonValueCodec[A]`
 * that is in implicit scope is automatically promoted to a `NatsCodec[A]`.
 *
 * ==Typical usage==
 *
 * {{{
 * import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
 * import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
 *
 * case class Person(name: String, age: Int)
 * object Person {
 *   given JsonValueCodec[Person] = JsonCodecMaker.make
 * }
 *
 * // NatsCodec[Person] is now available via NatsCodecJsoniter.fromJsonValueCodec
 * nats.publish(Subject("persons"), Person("Alice", 30))
 * }}}
 *
 * For an explicit one-off codec, use the [[NatsCodec.fromJsoniter]] extension
 * method:
 *
 * {{{
 * val codec: NatsCodec[Person] = NatsCodec.fromJsoniter(JsonCodecMaker.make[Person])
 * }}}
 */
object NatsCodecJsoniter {

  /**
   * Wrap a `JsonValueCodec[A]` as a [[NatsCodec]][A].
   *
   * Encoding delegates to `writeToArray`; decoding delegates to `readFromArray`
   * and maps any thrown exception (typically a `JsonReaderException`) to
   * [[NatsDecodeError]].
   *
   * @param codec
   *   A jsoniter-scala `JsonValueCodec[A]`.
   */
  def wrap[A](codec: JsonValueCodec[A]): NatsCodec[A] = new NatsCodec[A] {
    def encode(value: A): Chunk[Byte] =
      Chunk.fromArray(writeToArray(value)(codec))

    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
      Try(readFromArray[A](bytes.toArray)(codec)).toEither.left
        .map(e => NatsDecodeError(e.getMessage))
  }

}
