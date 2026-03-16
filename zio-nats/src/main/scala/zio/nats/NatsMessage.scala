package zio.nats

import io.nats.client.{Message => JMessage}
import io.nats.client.impl.{Headers => JHeaders, NatsMessage => JNatsMessage}
import zio._

import scala.jdk.CollectionConverters._

/**
 * Immutable, pure NATS message.
 *
 * The primary message type for core pub/sub and request/reply operations.
 * Contains no Java types and is safe to store, copy, and pattern-match.
 *
 * For JetStream messages that require acknowledgment, see [[JetStreamMessage]].
 *
 * @param subject
 *   The subject this message was published to.
 * @param replyTo
 *   Optional reply-to subject (set on request-reply messages).
 * @param headers
 *   NATS message headers.
 * @param payload
 *   Raw message payload bytes.
 */
final case class NatsMessage(
  subject: Subject,
  replyTo: Option[Subject],
  headers: Headers,
  payload: Chunk[Byte]
) {

  /**
   * Decode the payload using the implicit [[NatsCodec]].
   *
   * @return
   *   Right(value) on success, Left([[NatsDecodeError]]) on failure.
   */
  def decode[A](implicit codec: NatsCodec[A]): Either[NatsDecodeError, A] =
    codec.decode(payload)

  /** UTF-8 string representation of the payload. */
  def dataAsString: String =
    new String(payload.toArray, java.nio.charset.StandardCharsets.UTF_8)

  /** @deprecated Use [[payload]] instead. */
  @deprecated("Use payload instead", since = "0.2.0")
  def data: Chunk[Byte] = payload
}

object NatsMessage {

  /** Build a pure [[NatsMessage]] from a jnats [[JMessage]]. */
  private[nats] def fromJava(msg: JMessage): NatsMessage = {
    val hdrs: Headers =
      if (msg.hasHeaders && msg.getHeaders != null) {
        val m = msg.getHeaders
          .keySet()
          .asScala
          .map(key => key -> Chunk.fromIterable(msg.getHeaders.get(key).asScala))
          .toMap
        Headers(m)
      } else Headers.empty

    NatsMessage(
      subject = Subject(msg.getSubject),
      replyTo = Option(msg.getReplyTo).map(Subject(_)),
      headers = hdrs,
      payload = Chunk.fromArray(Option(msg.getData).getOrElse(Array.emptyByteArray))
    )
  }

  /**
   * Build a jnats [[JMessage]] suitable for publishing.
   *
   * Used internally by [[NatsLive]] and [[JetStreamLive]].
   */
  private[nats] def toJava(
    subject: String,
    data: Chunk[Byte],
    replyTo: Option[String] = None,
    headers: Headers = Headers.empty
  ): JMessage = {
    val builder = JNatsMessage
      .builder()
      .subject(subject)
      .data(data.toArray)

    replyTo.foreach(builder.replyTo)

    if (headers.nonEmpty) {
      val jHeaders = new JHeaders()
      headers.values.foreach { case (key, values) =>
        jHeaders.add(key, values.toList.asJava)
      }
      builder.headers(jHeaders)
    }

    builder.build()
  }
}
