package zio.nats.serialization

import zio.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.codec.{BinaryCodec, BinaryFormat, Format, TextCodec, TextFormat}

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Internal encode/decode bridge between zio-blocks Format and raw bytes.
  *
  * Dispatches on BinaryFormat (ByteBuffer) vs TextFormat (CharBuffer) — the
  * two concrete subtypes of the sealed Format trait — and handles buffer
  * allocation transparently. Users never see this object; they simply pass a
  * Format value (JsonFormat, AvroFormat, etc.) in NatsConfig.
  *
  * The casts to BinaryCodec/TextCodec are safe: BinaryFormat[TC] constrains
  * TC[A] <: BinaryCodec[A], and TextFormat[TC] constrains TC[A] <: TextCodec[A].
  */
private[nats] object NatsSerializer {

  def encode[T: Schema](value: T, format: Format): Either[Throwable, Chunk[Byte]] =
    try {
      format match {
        case bf: BinaryFormat[_] =>
          val codec = Schema[T].deriving(bf.deriver).derive.asInstanceOf[BinaryCodec[T]]
          Right(Chunk.fromArray(encodeBinary(codec, value)))
        case tf: TextFormat[_] =>
          val codec = Schema[T].deriving(tf.deriver).derive.asInstanceOf[TextCodec[T]]
          Right(Chunk.fromArray(encodeText(codec, value)))
      }
    } catch { case NonFatal(e) => Left(e) }

  def decode[T: Schema](bytes: Chunk[Byte], format: Format): Either[Throwable, T] =
    try {
      format match {
        case bf: BinaryFormat[_] =>
          val codec = Schema[T].deriving(bf.deriver).derive.asInstanceOf[BinaryCodec[T]]
          codec.decode(ByteBuffer.wrap(bytes.toArray))
            .left.map(e => new Exception(e.toString))
        case tf: TextFormat[_] =>
          val codec = Schema[T].deriving(tf.deriver).derive.asInstanceOf[TextCodec[T]]
          codec.decode(UTF_8.decode(ByteBuffer.wrap(bytes.toArray)))
            .left.map(e => new Exception(e.toString))
      }
    } catch { case NonFatal(e) => Left(e) }

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
