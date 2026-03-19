package zio.nats

/**
 * Represents a failure to decode bytes into a typed value.
 *
 * Distinct from [[NatsError]] so that codec boundaries can be pattern-matched
 * independently from transport errors. Returned from [[NatsCodec.decode]] and
 * [[NatsMessage.decode]].
 *
 * @param message
 *   Human-readable description of the failure.
 * @param cause
 *   The underlying cause, if available.
 */
final case class NatsDecodeError(message: String, cause: Option[Throwable] = None)
    extends Exception(message, cause.orNull) {
  override def toString: String = s"NatsDecodeError($message)"
}
