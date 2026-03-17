package zio.nats

import zio.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.codec.Format
import zio.nats.serialization.NatsSerializer

import java.nio.charset.StandardCharsets

/**
 * Typeclass for encoding and decoding NATS message payloads.
 *
 * Instances are resolved at compile time via Scala's `given`/`using` mechanism
 * and can be derived from any [[zio.blocks.schema.codec.Format]] paired with a
 * [[zio.blocks.schema.Schema]].
 *
 * ==Deriving a default codec==
 *
 * {{{
 * val codecs = NatsCodec.fromFormat(JsonFormat)
 * import codecs.derived       // Scala 3: brings NatsCodec[A] into scope for all A: Schema
 * // import codecs._          // Scala 2.13 equivalent
 * }}}
 *
 * ==Overriding per type==
 *
 * {{{
 * given auditCodec: NatsCodec[AuditEvent] =
 *   NatsCodec.fromFormat(BsonFormat).derived[AuditEvent]
 * }}}
 *
 * @tparam A
 *   The type to encode/decode.
 */
trait NatsCodec[A] {

  /** Encode a value to bytes. Implementations should not throw. */
  def encode(value: A): Chunk[Byte]

  /**
   * Decode bytes to a value.
   *
   * @return
   *   Right(value) on success, Left([[NatsDecodeError]]) on failure.
   */
  def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A]
}

object NatsCodec {

  /** Summon a [[NatsCodec]] instance for type A. */
  def apply[A](using codec: NatsCodec[A]): NatsCodec[A] = codec

  /**
   * Create a [[Builder]] that derives [[NatsCodec]] instances from the given
   * serialization format.
   *
   * {{{
   * val codecs = NatsCodec.fromFormat(JsonFormat)
   * import codecs.derived
   * }}}
   *
   * @param format
   *   A zio-blocks serialization format (e.g. JsonFormat).
   */
  def fromFormat(format: Format): Builder = new Builder(format)

  /**
   * Derive a codec directly from a format.
   *
   * {{{
   * given codec: NatsCodec[Person] = NatsCodec.derived[Person](JsonFormat)
   * }}}
   */
  def derived[A: Schema](format: Format): NatsCodec[A] = fromFormat(format).derived[A]

  // ---------------------------------------------------------------------------
  // Built-in primitive codecs
  // ---------------------------------------------------------------------------

  /** Identity codec for raw byte payloads. */
  given bytesCodec: NatsCodec[Chunk[Byte]] = new NatsCodec[Chunk[Byte]] {
    def encode(value: Chunk[Byte]): Chunk[Byte]                          = value
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, Chunk[Byte]] = Right(bytes)
  }

  /** UTF-8 string codec. */
  given stringCodec: NatsCodec[String] = new NatsCodec[String] {
    def encode(value: String): Chunk[Byte] =
      Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, String] =
      Right(new String(bytes.toArray, StandardCharsets.UTF_8))
  }

  // ---------------------------------------------------------------------------
  // Builder: derives NatsCodec from a Format + Schema
  // ---------------------------------------------------------------------------

  /**
   * Derives [[NatsCodec]] instances from a [[Format]].
   *
   * Import `builder.derived` to bring a default codec for all Schema-annotated
   * types into scope.
   *
   * {{{
   * val codecs = NatsCodec.fromFormat(JsonFormat)
   * import codecs.derived
   * // NatsCodec[A] is now available for any A with Schema[A] in scope
   * }}}
   */
  final class Builder private[NatsCodec] (val format: Format) {

    /**
     * Derive a [[NatsCodec]] for type A using this builder's format.
     *
     * Import this method to make [[NatsCodec]] available for all types that
     * have a given [[zio.blocks.schema.Schema]].
     *
     *   `import builder.derived`
     */
    given derived[A: Schema]: NatsCodec[A] = new NatsCodec[A] {

      def encode(value: A): Chunk[Byte] =
        NatsSerializer.encode(value, format) match {
          case Right(bytes) => bytes
          case Left(e)      =>
            throw new RuntimeException(
              s"NatsCodec encode failed for ${value.getClass.getSimpleName}: ${e.getMessage}",
              e
            )
        }

      def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
        NatsSerializer
          .decode[A](bytes, format)
          .left
          .map(e => NatsDecodeError(e.getMessage, Some(e)))
    }
  }
}
