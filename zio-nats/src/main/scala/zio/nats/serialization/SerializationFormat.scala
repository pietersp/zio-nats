package zio.nats.serialization

import zio.Chunk
import zio.blocks.schema.Schema
import scala.reflect.ClassTag

trait SerializationFormat {
  def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]]
  def decode[T: Schema](bytes: Chunk[Byte])(implicit tag: scala.reflect.ClassTag[T]): Either[Throwable, T]
}

object SerializationFormat {
  val json: SerializationFormat = JsonFormat
  val messagePack: SerializationFormat = MessagePackFormat
}

object JsonFormat extends SerializationFormat {
  override def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]] = {
    val encoder = new JsonEncoder[T]
    encoder.encode(value)
  }

  override def decode[T: Schema](bytes: Chunk[Byte])(implicit tag: scala.reflect.ClassTag[T]): Either[Throwable, T] = {
    val decoder = new JsonDecoder[T]
    decoder.decode(bytes)
  }
}

class JsonEncoder[T] {
  def encode(value: T): Either[Throwable, Chunk[Byte]] = {
    try {
      value match {
        case p: Product =>
          val fields = p.productElementNames.zip(p.productIterator).map { case (k, v) =>
            val vStr = encodeValue(v)
            s""""$k":$vStr"""
          }.mkString("{", ",", "}")
          Right(Chunk.fromArray(fields.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        case _ =>
          val jsonStr = s""""$value""""
          Right(Chunk.fromArray(jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      }
    } catch {
      case e: Throwable => Left(e)
    }
  }

  private def encodeValue(value: Any): String = {
    value match {
      case p: Product =>
        val fields = p.productElementNames.zip(p.productIterator).map { case (k, v) =>
          val vStr = encodeValue(v)
          s""""$k":$vStr"""
        }.mkString("{", ",", "}")
        fields
      case s: String => s""""$s""""
      case n: Int => n.toString
      case n: Long => n.toString
      case n: Double => n.toString
      case n: Float => n.toString
      case b: Boolean => b.toString
      case null => "null"
      case other => s""""$other""""
    }
  }
}

class JsonDecoder[T](implicit tag: scala.reflect.ClassTag[T]) {
  def decode(bytes: Chunk[Byte]): Either[Throwable, T] = {
    try {
      val jsonStr = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
      val parsed = parseJson(jsonStr)
      constructFromJson[T](parsed)
    } catch {
      case e: Throwable => Left(e)
    }
  }

  private def parseJson(json: String): Any = {
    val trimmed = json.trim
    if (trimmed.startsWith("{")) {
      val content = trimmed.substring(1, trimmed.length - 1)
      if (content.isEmpty) Map.empty[String, Any]
      else {
        val parts = splitAtTopLevel(content)
        val pairs = parts.map { pair =>
          val colonIdx = pair.indexOf(":")
          val key = pair.substring(0, colonIdx).trim.replaceAll("^\"|\"$", "")
          val value = parseJson(pair.substring(colonIdx + 1).trim)
          (key, value)
        }.toMap
        pairs
      }
    } else if (trimmed.startsWith("\"")) {
      trimmed.replaceAll("^\"|\"$", "")
    } else if (trimmed == "null") {
      null.asInstanceOf[Any]
    } else if (trimmed == "true") {
      true
    } else if (trimmed == "false") {
      false
    } else {
      trimmed.toIntOption.getOrElse(trimmed.toDoubleOption.getOrElse(trimmed))
    }
  }

  private def splitAtTopLevel(s: String): Array[String] = {
    val result = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var inString = false
    var current = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s(i)
      if (c == '"' && (i == 0 || s(i - 1) != '\\')) {
        inString = !inString
        current += c
      } else if (!inString) {
        c match {
          case '{' | '[' => depth += 1; current += c
          case '}' | ']' => depth -= 1; current += c
          case ',' if depth == 0 =>
            result += current.toString.trim
            current = new StringBuilder
          case _ => current += c
        }
      } else {
        current += c
      }
      i += 1
    }
    if (current.nonEmpty) result += current.toString.trim
    result.toArray
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def constructFromJson[T](parsed: Any)(implicit tag: ClassTag[T]): Either[Throwable, T] = {
    try {
      parsed match {
        case m: Map[_, _] =>
          val map = m.asInstanceOf[Map[String, Any]]
          val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
          val constructors = clazz.getDeclaredConstructors
          val constructor = constructors(0)
          constructor.setAccessible(true)
          val params = constructor.getParameters
          val args = params.map { param =>
            val name = param.getName
            val convertedName = toCamelCase(name)
            map.get(convertedName).getOrElse(
              map.get(toSnakeCase(convertedName)).getOrElse(
                throw new IllegalArgumentException(s"Missing parameter: $name")
              )
            )
          }
          val instance = constructor.newInstance(args*).asInstanceOf[T]
          Right(instance)
        case _ =>
          Left(new IllegalArgumentException("Expected JSON object"))
      }
    } catch {
      case e: Throwable => Left(e)
    }
  }

  private def toSnakeCase(name: String): String = {
    val sb = new StringBuilder
    for (i <- 0 until name.length) {
      val c = name(i)
      if (c.isUpper && i > 0) sb.append('_')
      sb.append(c.toLower)
    }
    sb.toString
  }

  private def toCamelCase(name: String): String = {
    val parts = name.split("_")
    if (parts.length == 1) name
    else parts.zipWithIndex.map { case (part, idx) =>
      if (idx == 0) part
      else part.capitalize
    }.mkString
  }
}

object MessagePackFormat extends SerializationFormat {
  override def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]] = {
    Left(new NotImplementedError("MessagePack encoding not implemented"))
  }

  override def decode[T: Schema](bytes: Chunk[Byte])(implicit tag: scala.reflect.ClassTag[T]): Either[Throwable, T] = {
    Left(new NotImplementedError("MessagePack decoding not implemented"))
  }
}