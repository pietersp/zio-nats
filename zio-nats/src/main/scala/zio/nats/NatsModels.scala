package zio.nats

import io.nats.client.{Statistics as JStatistics}

// ---------------------------------------------------------------------------
// Envelope — typed value + raw NatsMessage
// ---------------------------------------------------------------------------

/**
 * A decoded message value paired with its raw [[NatsMessage]].
 *
 * Returned by [[Nats.request]] and [[Nats.subscribe]] so callers have access
 * to both the decoded payload and the full message metadata (headers,
 * subject, reply-to, raw bytes).
 *
 * {{{
 * Nats.subscribe[UserEvent](subject).map { env =>
 *   // env.value   — the decoded UserEvent
 *   // env.message — the full NatsMessage (headers, subject, etc.)
 * }
 * }}}
 *
 * @param value
 *   The decoded payload.
 * @param message
 *   The raw [[NatsMessage]] that was received.
 */
final case class Envelope[+A](value: A, message: NatsMessage)

// ---------------------------------------------------------------------------
// Connection statistics
// ---------------------------------------------------------------------------

/**
 * Lifetime counters for a NATS connection.
 *
 * All values are monotonically increasing totals since the connection was
 * established. Obtained via [[Nats.statistics]].
 */
final case class ConnectionStats(
  inMsgs: Long,
  outMsgs: Long,
  inBytes: Long,
  outBytes: Long,
  reconnects: Long,
  droppedCount: Long,
  pings: Long,
  oks: Long,
  errs: Long,
  exceptions: Long,
  requestsSent: Long,
  repliesReceived: Long,
  flushCounter: Long,
  outstandingRequests: Long
)

private[nats] object ConnectionStats {
  def fromJava(s: JStatistics): ConnectionStats = ConnectionStats(
    inMsgs = s.getInMsgs,
    outMsgs = s.getOutMsgs,
    inBytes = s.getInBytes,
    outBytes = s.getOutBytes,
    reconnects = s.getReconnects,
    droppedCount = s.getDroppedCount,
    pings = s.getPings,
    oks = s.getOKs,
    errs = s.getErrs,
    exceptions = s.getExceptions,
    requestsSent = s.getRequestsSent,
    repliesReceived = s.getRepliesReceived,
    flushCounter = s.getFlushCounter,
    outstandingRequests = s.getOutstandingRequests
  )
}
