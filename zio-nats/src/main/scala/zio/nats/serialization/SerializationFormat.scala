package zio.nats.serialization

import scala.util.control.NonFatal
import zio.Chunk
import zio.blocks.schema._
import zio.blocks.schema.json._

trait SerializationFormat {
  def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]]
  def decode[T: Schema](bytes: Chunk[Byte]): Either[Throwable, T]
}

object SerializationFormat {
  val json: SerializationFormat = JsonFormatImpl
}

private object JsonFormatImpl extends SerializationFormat {
  override def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]] = {
    try {
      val codec = Schema[T].derive(JsonFormat)
      Right(Chunk.fromArray(codec.encode(value)))
    } catch {
      case NonFatal(e) => Left(e)
    }
  }

  override def decode[T: Schema](bytes: Chunk[Byte]): Either[Throwable, T] = {
    try {
      val codec = Schema[T].derive(JsonFormat)
      codec.decode(bytes.toArray) match {
        case Right(v) => Right(v)
        case Left(e)  => Left(new Exception(e.toString))
      }
    } catch {
      case NonFatal(e) => Left(e)
    }
  }
}
