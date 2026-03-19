package zio.nats

import zio.blocks.schema.Schema
import zio.blocks.schema.codec.Format

/**
 * Extension methods on [[NatsCodec.type]] for zio-blocks integration.
 *
 * These are automatically available via `import zio.nats.*` when
 * `zio-nats-zio-blocks` is on the classpath — existing code requires no
 * migration.
 *
 * {{{
 * val codecs = NatsCodec.fromFormat(JsonFormat)
 * import codecs.derived
 *
 * given codec: NatsCodec[Person] = NatsCodec.derived[Person](JsonFormat)
 * }}}
 */
extension (companion: NatsCodec.type)

  /**
   * Create a [[NatsCodecZioBlocks.Builder]] that derives [[NatsCodec]]
   * instances from the given serialization format.
   *
   * {{{
   * val codecs = NatsCodec.fromFormat(JsonFormat)
   * import codecs.derived
   * }}}
   *
   * @param format
   *   A zio-blocks serialization format (e.g. JsonFormat).
   */
  def fromFormat(format: Format): NatsCodecZioBlocks.Builder =
    new NatsCodecZioBlocks.Builder(format)

  /**
   * Derive a [[NatsCodec]] directly from a format and a schema.
   *
   * {{{
   * given codec: NatsCodec[Person] = NatsCodec.derived[Person](JsonFormat)
   * }}}
   */
  def derived[A: Schema](format: Format): NatsCodec[A] =
    new NatsCodecZioBlocks.Builder(format).derived[A]
