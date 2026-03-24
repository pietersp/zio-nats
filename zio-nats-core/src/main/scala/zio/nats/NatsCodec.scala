package zio.nats

import zio.Chunk

import java.nio.charset.StandardCharsets

/**
 * Typeclass for encoding and decoding NATS message payloads.
 *
 * Instances are resolved at compile time via Scala's `given`/`using` mechanism.
 * Built-in instances handle `Chunk[Byte]` (identity) and `String` (UTF-8).
 *
 * For domain types with zio-blocks schemas, add `zio-nats-zio-blocks` to your
 * dependencies and use `NatsCodec.fromFormat(JsonFormat)`.
 *
 * ==Custom codec (no framework required)==
 *
 * {{{
 * given myCodec: NatsCodec[MyType] = new NatsCodec[MyType] {
 *   def encode(value: MyType): Chunk[Byte] = ???
 *   def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, MyType] = ???
 * }
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
}

// ---------------------------------------------------------------------------
// ErrorCodecOrNothing — internal bridge for service framework error encoding
// ---------------------------------------------------------------------------

/**
 * Internal type class that provides [[NatsCodec]]-like behaviour for a service
 * handler's error type `E`, including a safe instance for `Nothing`.
 *
 * Used by [[zio.nats.service.ServiceEndpoint]] to encode domain errors into the
 * reply body on the server side, and by [[zio.nats.Nats.requestService]] to
 * decode them on the client side.
 *
 * Users never interact with this type class directly: it is resolved
 * automatically from whatever `NatsCodec[E]` is in scope when
 * [[zio.nats.service.ServiceEndpoint.withError]] is called.
 */
private[nats] sealed trait ErrorCodecOrNothing[E]:
  def encode(e: E): Chunk[Byte]
  def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, E]

private[nats] object ErrorCodecOrNothing:

  /**
   * Safe instance for infallible endpoints (`Err = Nothing`).
   *
   * Both methods are statically unreachable: a handler typed `IO[Nothing, Out]`
   * can never produce a `Nothing` value.
   */
  given ErrorCodecOrNothing[Nothing] with
    def encode(e: Nothing): Chunk[Byte]                              = e // unreachable
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, Nothing] =
      Left(NatsDecodeError("Unexpected typed error payload for infallible endpoint"))

  /** Derive an instance from any `NatsCodec[E]` in scope. */
  given fromNatsCodec[E](using c: NatsCodec[E]): ErrorCodecOrNothing[E] with
    def encode(e: E): Chunk[Byte]                              = c.encode(e)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, E] = c.decode(bytes)
