package zio.nats

import io.nats.client.{Message => JMessage}
import zio._

import scala.jdk.CollectionConverters._

/**
 * A NATS message from a JetStream consumer, augmented with acknowledgment
 * operations.
 *
 * Contains the same payload data as [[NatsMessage]] but also provides methods
 * to ack/nak the message within the JetStream at-least-once delivery model.
 *
 * Obtained from [[Consumer.fetch]], [[Consumer.consume]], [[Consumer.iterate]],
 * or [[Consumer.next]].
 *
 * @param subject
 *   The message subject.
 * @param replyTo
 *   Optional reply-to subject.
 * @param headers
 *   NATS headers attached to the message.
 * @param payload
 *   Raw message payload bytes.
 * @param underlying
 *   The underlying jnats [[JMessage]] (used for ack only).
 */
final class JetStreamMessage(
  val subject: Subject,
  val replyTo: Option[Subject],
  val headers: Headers,
  val payload: Chunk[Byte],
  private[nats] val underlying: JMessage
) {

  /** Return the corresponding pure [[NatsMessage]] (no ack capability). */
  def message: NatsMessage = NatsMessage(subject, replyTo, headers, payload)

  /** Decode the payload using the given [[NatsCodec]]. */
  def decode[A](implicit codec: NatsCodec[A]): Either[NatsDecodeError, A] =
    codec.decode(payload)

  /** UTF-8 string representation of the payload. */
  def dataAsString: String =
    new String(payload.toArray, java.nio.charset.StandardCharsets.UTF_8)

  /** Returns true if this message was delivered via JetStream. */
  def isJetStream: Boolean = underlying.isJetStream

  // -------------------------------------------------------------------------
  // JetStream acknowledgment operations (only valid when isJetStream is true)
  // -------------------------------------------------------------------------

  /** Acknowledge successful processing. */
  def ack: IO[NatsError, Unit] =
    ZIO.attempt(underlying.ack()).mapError(NatsError.fromThrowable)

  /**
   * Synchronous ack — blocks until the server confirms receipt.
   *
   * @param timeout
   *   Maximum time to wait for the server ack.
   */
  def ackSync(timeout: Duration): IO[NatsError, Unit] =
    ZIO
      .attemptBlocking(underlying.ackSync(timeout.asJava))
      .unit
      .mapError(NatsError.fromThrowable)

  /** Negative-acknowledge — ask the server to redeliver this message. */
  def nak: IO[NatsError, Unit] =
    ZIO.attempt(underlying.nak()).mapError(NatsError.fromThrowable)

  /**
   * Negative-acknowledge with a redelivery delay.
   *
   * @param delay
   *   How long the server should wait before redelivering.
   */
  def nakWithDelay(delay: Duration): IO[NatsError, Unit] =
    ZIO.attempt(underlying.nakWithDelay(delay.asJava)).mapError(NatsError.fromThrowable)

  /** Terminate this message — it will not be redelivered. */
  def term: IO[NatsError, Unit] =
    ZIO.attempt(underlying.term()).mapError(NatsError.fromThrowable)

  /** Extend the ack deadline (signal work in progress). */
  def inProgress: IO[NatsError, Unit] =
    ZIO.attempt(underlying.inProgress()).mapError(NatsError.fromThrowable)

  override def toString: String =
    s"JetStreamMessage(subject=$subject, payload=${payload.length} bytes)"
}

object JetStreamMessage {

  /** Convert a jnats [[JMessage]] to a [[JetStreamMessage]]. */
  private[nats] def fromJava(msg: JMessage): JetStreamMessage = {
    val hdrs: Headers =
      if (msg.hasHeaders && msg.getHeaders != null) {
        val m = msg.getHeaders
          .keySet()
          .asScala
          .map(key => key -> Chunk.fromIterable(msg.getHeaders.get(key).asScala))
          .toMap
        Headers(m)
      } else Headers.empty

    new JetStreamMessage(
      subject = Subject(msg.getSubject),
      replyTo = Option(msg.getReplyTo).map(Subject(_)),
      headers = hdrs,
      payload = Chunk.fromArray(Option(msg.getData).getOrElse(Array.emptyByteArray)),
      underlying = msg
    )
  }
}
