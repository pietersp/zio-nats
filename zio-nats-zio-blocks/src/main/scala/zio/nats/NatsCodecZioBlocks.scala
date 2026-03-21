package zio.nats

import zio.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.codec.Format
import zio.nats.serialization.NatsSerializer

import java.util.concurrent.ConcurrentHashMap

/**
 * zio-blocks integration for [[NatsCodec]].
 *
 * Use [[NatsCodecZioBlocks.Builder]] (or the `NatsCodec.fromFormat` extension
 * method provided by `NatsCodecZioBlocksExtensions`) to derive [[NatsCodec]]
 * instances from any zio-blocks [[zio.blocks.schema.codec.Format]].
 *
 * {{{
 * val codecs = NatsCodec.fromFormat(JsonFormat)
 * import codecs.derived
 * // NatsCodec[A] is now available for any A with Schema[A] in scope
 * }}}
 */
object NatsCodecZioBlocks {

  /**
   * Derives [[NatsCodec]] instances from a zio-blocks [[zio.blocks.schema.codec.Format]].
   *
   * Import `builder.derived` to bring a default codec for all Schema-annotated
   * types into scope.
   *
   * {{{
   * val codecs = NatsCodec.fromFormat(JsonFormat)
   * import codecs.derived
   * // NatsCodec[A] is now available for any A with Schema[A] in scope
   * }}}
   *
   * @param format
   *   A zio-blocks serialization format (e.g. JsonFormat).
   */
  final class Builder(val format: Format) {

    // Cache keyed on Schema[A] instance. Since polymorphic givens behave as
    // implicit defs, each call site would otherwise re-derive. Keying on the
    // Schema instance works because Schema instances are typically stable vals
    // in companion objects.
    private val cache = new ConcurrentHashMap[Any, NatsCodec[?]]()

    /**
     * Derive a [[NatsCodec]] for type `A` using this builder's format.
     *
     * The underlying format codec is built the first time this `given` is
     * resolved for a particular type `A`, then cached in this [[Builder]]
     * instance. Subsequent resolutions for the same `A` return the cached codec
     * without re-deriving.
     *
     * If the format cannot derive a codec for `A` — for example because no
     * implicit `Deriver` is available — an exception is thrown on the first
     * resolution, failing fast rather than hiding the error inside an `encode`
     * call.
     *
     * Import this method to make [[NatsCodec]] available for all types that
     * have a given [[zio.blocks.schema.Schema]].
     *
     * {{{
     * import builder.derived
     * }}}
     */
    given derived[A: Schema]: NatsCodec[A] = {
      val schema = summon[Schema[A]]
      cache
        .computeIfAbsent(
          schema,
          _ => {
            // Eagerly derive the compiled codec. Throws here if the format
            // cannot handle A — never inside encode.
            val compiledCodec = NatsSerializer.makeFor[A](format)
            new NatsCodec[A] {
              def encode(value: A): Chunk[Byte]                          = compiledCodec.encode(value)
              def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] = compiledCodec.decode(bytes)
            }
          }
        )
        .asInstanceOf[NatsCodec[A]]
    }
  }
}
