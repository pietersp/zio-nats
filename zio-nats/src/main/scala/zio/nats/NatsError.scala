package zio.nats

import io.nats.client.JetStreamApiException
import java.io.IOException

/**
 * Sealed error hierarchy for all NATS operations.
 *
 * Design:
 *   - Wraps jnats exceptions in typed case classes for exhaustive pattern
 *     matching
 *   - Extends NoStackTrace for performance (the cause carries the real stack
 *     trace)
 *   - Sub-sealed traits group errors by domain (JetStream, KV, ObjectStore)
 */
sealed trait NatsError extends Exception with scala.util.control.NoStackTrace {
  def message: String
  override def getMessage: String = message
}

object NatsError {

  // --- Connection errors ---

  final case class ConnectionFailed(message: String, cause: IOException) extends NatsError { initCause(cause) }

  final case class ConnectionClosed(message: String) extends NatsError

  final case class AuthenticationFailed(message: String, cause: IOException) extends NatsError { initCause(cause) }

  final case class Timeout(message: String) extends NatsError

  // --- Core messaging errors ---

  final case class PublishFailed(message: String, cause: Throwable) extends NatsError { initCause(cause) }

  final case class RequestFailed(message: String, cause: Throwable) extends NatsError { initCause(cause) }

  final case class SubscriptionFailed(message: String, cause: Throwable) extends NatsError { initCause(cause) }

  final case class SerializationError(message: String, cause: Throwable) extends NatsError { initCause(cause) }

  // --- JetStream errors ---

  sealed trait JetStreamError extends NatsError

  final case class JetStreamApiError(
    message: String,
    errorCode: Int,
    apiErrorCode: Int,
    cause: JetStreamApiException
  ) extends JetStreamError { initCause(cause) }

  final case class JetStreamPublishFailed(message: String, cause: Throwable) extends JetStreamError { initCause(cause) }

  final case class JetStreamConsumeFailed(message: String, cause: Throwable) extends JetStreamError { initCause(cause) }

  // --- Key-Value errors ---

  sealed trait KeyValueError extends NatsError

  final case class KeyValueOperationFailed(message: String, cause: Throwable) extends KeyValueError { initCause(cause) }

  final case class KeyNotFound(key: String) extends KeyValueError {
    val message: String = s"Key not found: $key"
  }

  // --- Object Store errors ---

  sealed trait ObjectStoreError extends NatsError

  final case class ObjectStoreOperationFailed(message: String, cause: Throwable) extends ObjectStoreError {
    initCause(cause)
  }

  // --- General fallback ---

  final case class GeneralError(message: String, cause: Throwable) extends NatsError { initCause(cause) }

  // --- Converter from raw Throwable ---

  private[nats] def fromThrowable(t: Throwable): NatsError = t match {
    case e: io.nats.client.AuthenticationException =>
      AuthenticationFailed(Option(e.getMessage).getOrElse("Authentication failed"), e)
    case e: JetStreamApiException =>
      JetStreamApiError(
        Option(e.getMessage).getOrElse("JetStream API error"),
        e.getErrorCode,
        e.getApiErrorCode,
        e
      )
    case e: IOException =>
      ConnectionFailed(Option(e.getMessage).getOrElse("Connection failed"), e)
    case e: java.util.concurrent.TimeoutException =>
      Timeout(Option(e.getMessage).getOrElse("Operation timed out"))
    case e: IllegalStateException =>
      ConnectionClosed(Option(e.getMessage).getOrElse("Connection closed"))
    case e =>
      GeneralError(Option(e.getMessage).getOrElse("Unknown error"), e)
  }
}
