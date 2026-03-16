package zio.nats

import io.nats.client.Message as JMessage
import io.nats.client.impl.{Headers as JHeaders, NatsMessage as JNatsMessage}
import zio.*
import zio.nats.subject.Subject

/** Immutable wrapper around a received NATS message.
  *
  * Data is eagerly copied to decouple from the connection lifecycle.
  * The underlying jnats Message is retained only for JetStream ack operations.
  */
final case class NatsMessage(
  subject: String,
  data: Chunk[Byte],
  replyTo: Option[Subject],
  headers: Map[String, List[String]],
  private[nats] val underlying: JMessage
) {

  /** UTF-8 string representation of the data payload. */
  def dataAsString: String =
    new String(data.toArray, java.nio.charset.StandardCharsets.UTF_8)

  // --- JetStream acknowledgment methods ---
  // Only valid for JetStream messages (underlying.isJetStream must be true).

  /** Acknowledge successful processing (JetStream). */
  def ack: IO[NatsError, Unit] =
    ZIO.attempt(underlying.ack()).mapError(NatsError.fromThrowable)

  /** Synchronous ack — waits for server confirmation (JetStream). */
  def ackSync(timeout: Duration): IO[NatsError, Unit] =
    ZIO.attemptBlocking(underlying.ackSync(timeout.asJava))
      .unit
      .mapError(NatsError.fromThrowable)

  /** Negative-acknowledge — request redelivery (JetStream). */
  def nak: IO[NatsError, Unit] =
    ZIO.attempt(underlying.nak()).mapError(NatsError.fromThrowable)

  /** Negative-acknowledge with a redelivery delay (JetStream). */
  def nakWithDelay(delay: Duration): IO[NatsError, Unit] =
    ZIO.attempt(underlying.nakWithDelay(delay.asJava)).mapError(NatsError.fromThrowable)

  /** Terminate — do not redeliver this message (JetStream). */
  def term: IO[NatsError, Unit] =
    ZIO.attempt(underlying.term()).mapError(NatsError.fromThrowable)

  /** Signal work in progress — extends the ack deadline (JetStream). */
  def inProgress: IO[NatsError, Unit] =
    ZIO.attempt(underlying.inProgress()).mapError(NatsError.fromThrowable)

  /** Returns true if this message came from JetStream. */
  def isJetStream: Boolean = underlying.isJetStream
}

object NatsMessage {

  /** Convert a jnats Message to an immutable NatsMessage. */
  private[nats] def fromJava(msg: JMessage): NatsMessage = {
    import scala.jdk.CollectionConverters.*
    val headers: Map[String, List[String]] =
      if (msg.hasHeaders && msg.getHeaders != null) {
        msg.getHeaders.keySet().asScala.map { key =>
          key -> msg.getHeaders.get(key).asScala.toList
        }.toMap
      } else Map.empty

    NatsMessage(
      subject    = msg.getSubject,
      data       = Chunk.fromArray(Option(msg.getData).getOrElse(Array.emptyByteArray)),
      replyTo    = Option(msg.getReplyTo).map(Subject(_)),
      headers    = headers,
      underlying = msg
    )
  }

  /** Build a jnats Message suitable for publishing. */
  private[nats] def toJava(
    subject: String,
    data: Chunk[Byte],
    replyTo: Option[String] = None,
    headers: Map[String, List[String]] = Map.empty
  ): JMessage = {
    import scala.jdk.CollectionConverters.*
    val builder = JNatsMessage.builder()
      .subject(subject)
      .data(data.toArray)

    replyTo.foreach(builder.replyTo)

    if (headers.nonEmpty) {
      val jHeaders = new JHeaders()
      headers.foreach { case (key, values) =>
        jHeaders.add(key, values.asJava)
      }
      builder.headers(jHeaders)
    }

    builder.build()
  }
}
