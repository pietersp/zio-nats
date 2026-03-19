package zio.nats

import play.api.libs.json.Format

/**
 * Extension method on [[NatsCodec.type]] for play-json integration.
 *
 * Available via `import zio.nats.*` when `zio-nats-play-json` is on the
 * classpath — no additional import required.
 *
 * {{{
 * val codec: NatsCodec[Person] = NatsCodec.fromPlayJson(Json.format[Person])
 * }}}
 */
extension (companion: NatsCodec.type)

  /**
   * Wrap a play-json `Format[A]` as a [[NatsCodec]][A].
   *
   * Use this for an explicit one-off codec. For automatic bridging via
   * implicit resolution, ensure a `given Format[A]` is in scope and
   * `import zio.nats.*` — [[fromPlayJsonFormat]] handles the rest.
   *
   * {{{
   * val codec: NatsCodec[Person] = NatsCodec.fromPlayJson(Json.format[Person])
   * }}}
   *
   * @param format
   *   A play-json `Format[A]` (typically obtained via `Json.format[Person]`
   *   or a manually written `Format` instance).
   */
  def fromPlayJson[A](format: Format[A]): NatsCodec[A] =
    NatsCodecPlayJson.wrap(format)