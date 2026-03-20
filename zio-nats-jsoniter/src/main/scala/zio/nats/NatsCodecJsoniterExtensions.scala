package zio.nats

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

/**
 * Extension method on [[NatsCodec.type]] for jsoniter-scala integration.
 *
 * Available via `import zio.nats.*` when `zio-nats-jsoniter` is on the
 * classpath — no additional import required.
 *
 * {{{
 * val codec: NatsCodec[Person] = NatsCodec.fromJsoniter(JsonCodecMaker.make[Person])
 * }}}
 */
extension (companion: NatsCodec.type)

  /**
   * Wrap a `JsonValueCodec[A]` as a [[NatsCodec]][A].
   *
   * Use this for an explicit one-off codec. For automatic bridging via implicit
   * resolution, ensure a `given JsonValueCodec[A]` is in scope and
   * `import zio.nats.*` — [[NatsCodecJsoniter.fromJsonValueCodec]] handles the
   * rest.
   *
   * {{{
   * val codec: NatsCodec[Person] = NatsCodec.fromJsoniter(JsonCodecMaker.make[Person])
   * }}}
   *
   * @param codec
   *   A jsoniter-scala `JsonValueCodec[A]` (typically obtained via
   *   `JsonCodecMaker.make`).
   */
  def fromJsoniter[A](codec: JsonValueCodec[A]): NatsCodec[A] =
    NatsCodecJsoniter.wrap(codec)
