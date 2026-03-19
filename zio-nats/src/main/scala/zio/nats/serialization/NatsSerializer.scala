package zio.nats.serialization

import zio.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.codec.{BinaryCodec, BinaryFormat, Format, TextCodec, TextFormat}
import zio.nats.NatsDecodeError

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec

/**
 * Internal encode/decode bridge between zio-blocks Format and raw bytes.
 *
 * Dispatches on BinaryFormat (ByteBuffer) vs TextFormat (CharBuffer) — the two
 * concrete subtypes of the sealed Format trait — and handles buffer allocation
 * transparently. Users never see this object; they interact with
 * [[zio.nats.NatsCodec]].
 *
 * The casts to BinaryCodec/TextCodec are safe: BinaryFormat[TC] constrains
 * TC[A] <: BinaryCodec[A], and TextFormat[TC] constrains TC[A] <: TextCodec[A].
 *
 * Use [[makeFor]] to build a [[CompiledCodec]] that pre-derives the format
 * codec once; the per-call encode/decode paths never re-derive.
 */
private[nats] object NatsSerializer {

  // ---------------------------------------------------------------------------
  // CompiledCodec — eagerly built, cache-safe, non-throwing on encode
  // ---------------------------------------------------------------------------

  /**
   * A pre-compiled, stateful codec for type `A`.
   *
   * Obtained via [[makeFor]], which derives the underlying format codec once at
   * construction. Any incompatibility between the format and the schema
   * surfaces as an exception thrown by [[makeFor]], not from [[encode]] or
   * [[decode]].
   *
   * @tparam A
   *   the type this codec can encode and decode.
   */
  sealed trait CompiledCodec[A] {

    /**
     * Encode a value to bytes.
     *
     * Does not throw for schema-related reasons (codec already validated at
     * construction). A throw here indicates a defect in the serialization
     * library (e.g. OOM) and should be treated as an unrecoverable error.
     */
    def encode(value: A): Chunk[Byte]

    /**
     * Decode bytes to a value.
     *
     * @return
     *   Right(value) on success, Left([[NatsDecodeError]]) on failure.
     */
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A]
  }

  /**
   * Eagerly build a [[CompiledCodec]] for type `A` from `format`.
   *
   * The format-specific codec is derived once here. If the format cannot derive
   * a codec for `A` (e.g. no implicit `Deriver` or incompatible schema), this
   * method throws — failing fast at codec construction time rather than
   * silently at the first `encode` / `decode` call.
   *
   * @throws RuntimeException
   *   (or format-specific exception) if the schema is incompatible with the
   *   format.
   */
  def makeFor[A: Schema](format: Format): CompiledCodec[A] = format match {
    case bf: BinaryFormat[_] =>
      val codec = Schema[A].deriving(bf.deriver).derive.asInstanceOf[BinaryCodec[A]]
      new BinaryCompiledCodec[A](codec)
    case tf: TextFormat[_] =>
      val codec = Schema[A].deriving(tf.deriver).derive.asInstanceOf[TextCodec[A]]
      new TextCompiledCodec[A](codec)
  }

  // ---------------------------------------------------------------------------
  // Private implementations
  // ---------------------------------------------------------------------------

  private final class BinaryCompiledCodec[A](codec: BinaryCodec[A]) extends CompiledCodec[A] {
    def encode(value: A): Chunk[Byte]                          = Chunk.fromArray(encodeBinary(codec, value))
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
      codec
        .decode(ByteBuffer.wrap(bytes.toArray))
        .left
        .map(e => NatsDecodeError(e.toString))
  }

  private final class TextCompiledCodec[A](codec: TextCodec[A]) extends CompiledCodec[A] {
    def encode(value: A): Chunk[Byte]                          = Chunk.fromArray(encodeText(codec, value))
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A] =
      codec
        .decode(UTF_8.decode(ByteBuffer.wrap(bytes.toArray)))
        .left
        .map(e => NatsDecodeError(e.toString))
  }

  @tailrec
  private def encodeBinary[A](codec: BinaryCodec[A], value: A, capacity: Int = 4096): Array[Byte] = {
    val buf = ByteBuffer.allocate(capacity)
    try {
      codec.encode(value, buf)
      buf.flip()
      val bytes = new Array[Byte](buf.remaining())
      buf.get(bytes)
      bytes
    } catch {
      case _: java.nio.BufferOverflowException => encodeBinary(codec, value, capacity * 2)
    }
  }

  @tailrec
  private def encodeText[A](codec: TextCodec[A], value: A, capacity: Int = 4096): Array[Byte] = {
    val buf = CharBuffer.allocate(capacity)
    try {
      codec.encode(value, buf)
      buf.flip()
      buf.toString.getBytes(UTF_8)
    } catch {
      case _: java.nio.BufferOverflowException => encodeText(codec, value, capacity * 2)
    }
  }
}
