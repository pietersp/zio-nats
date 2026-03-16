package zio.nats

import io.nats.client.{Connection => JConnection}
import zio.Chunk
import zio.nats.subject.Subject

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------

/**
 * Immutable NATS message headers.
 *
 * Each header key may have multiple values (HTTP-style multi-value headers).
 * Values are stored as [[Chunk]] for efficient append and no Java collections
 * in the public API.
 *
 * {{{
 * val h = Headers("Content-Type" -> "application/json", "X-Trace-Id" -> "abc")
 * h.get("Content-Type")           // Chunk("application/json")
 * h.add("X-Extra", "value").get("X-Extra") // Chunk("value")
 * }}}
 *
 * @param values
 *   A map from header name to all its values.
 */
final case class Headers(values: Map[String, Chunk[String]]) {

  /** Get all values for the given header key (empty Chunk if absent). */
  def get(key: String): Chunk[String] = values.getOrElse(key, Chunk.empty)

  /** Returns true if no headers are set. */
  def isEmpty: Boolean = values.isEmpty

  /** Returns true if at least one header is set. */
  def nonEmpty: Boolean = values.nonEmpty

  /** Append a single value to the given header key. */
  def add(key: String, value: String): Headers =
    copy(values = values + (key -> (get(key) :+ value)))

  /** Replace all values for the given header key with a single value. */
  def set(key: String, value: String): Headers =
    copy(values = values + (key -> Chunk.single(value)))

  /** Merge two [[Headers]], combining values for duplicate keys. */
  def ++(other: Headers): Headers =
    copy(values = other.values.foldLeft(values) { case (acc, (k, vs)) =>
      acc + (k -> (acc.getOrElse(k, Chunk.empty) ++ vs))
    })
}

object Headers {

  /** Empty headers instance. */
  val empty: Headers = Headers(Map.empty[String, Chunk[String]])

  /**
   * Construct [[Headers]] from key-value pairs.
   *
   * Multiple pairs with the same key are combined into a multi-value header.
   *
   * {{{
   * Headers("X-Header" -> "a", "X-Header" -> "b")
   * // Headers(Map("X-Header" -> Chunk("a", "b")))
   * }}}
   */
  def apply(pairs: (String, String)*): Headers =
    pairs.foldLeft(empty) { case (h, (k, v)) => h.add(k, v) }

  /**
   * Construct [[Headers]] from a legacy `Map[String, List[String]]`.
   *
   * Useful when bridging from older code or raw jnats headers.
   */
  def fromMap(map: Map[String, List[String]]): Headers =
    Headers(map.view.mapValues(Chunk.fromIterable(_)).toMap)
}

// ---------------------------------------------------------------------------
// QueueGroup
// ---------------------------------------------------------------------------

/**
 * An opaque type alias for NATS queue group names.
 *
 * When multiple subscribers share the same [[QueueGroup]], each published
 * message is delivered to exactly one subscriber in the group.
 */
opaque type QueueGroup = String

object QueueGroup {

  /** Create a [[QueueGroup]] from a raw string. */
  def apply(value: String): QueueGroup = value

  extension (q: QueueGroup) def value: String = q
}

// ---------------------------------------------------------------------------
// PublishParams
// ---------------------------------------------------------------------------

/**
 * Optional parameters for a NATS publish operation.
 *
 * Named `PublishParams` rather than `PublishOptions` to avoid a collision with
 * the JetStream [[PublishOptions]] type.
 *
 * @param headers
 *   Optional NATS headers to attach to the message.
 * @param replyTo
 *   Optional reply-to subject (for manual request/reply).
 */
final case class PublishParams(
  headers: Headers = Headers.empty,
  replyTo: Option[Subject] = None
)

object PublishParams {

  /** Default (empty) params: no headers, no reply-to. */
  val empty: PublishParams = PublishParams()
}

// ---------------------------------------------------------------------------
// ConnectionStatus
// ---------------------------------------------------------------------------

/**
 * Scala representation of the NATS connection lifecycle state.
 *
 * Mirrors [[io.nats.client.Connection.Status]] without exposing the Java type
 * in the public API.
 */
sealed trait ConnectionStatus

object ConnectionStatus {

  /** The connection is fully established and ready for use. */
  case object Connected extends ConnectionStatus

  /** Performing the initial connection handshake. */
  case object Connecting extends ConnectionStatus

  /** Attempting to reconnect after a disconnection. */
  case object Reconnecting extends ConnectionStatus

  /** The connection has been dropped and is not reconnecting. */
  case object Disconnected extends ConnectionStatus

  /** The connection has been permanently closed. */
  case object Closed extends ConnectionStatus

  private[nats] def fromJava(s: JConnection.Status): ConnectionStatus = s match {
    case JConnection.Status.CONNECTED    => Connected
    case JConnection.Status.CONNECTING   => Connecting
    case JConnection.Status.RECONNECTING => Reconnecting
    case JConnection.Status.CLOSED       => Closed
    case _                               => Disconnected
  }
}

// ---------------------------------------------------------------------------
// NatsServerInfo
// ---------------------------------------------------------------------------

/**
 * Server information returned after a successful NATS connection.
 *
 * This is a Scala wrapper that avoids leaking [[io.nats.client.api.ServerInfo]]
 * into user code.
 *
 * @param serverId
 *   The unique server ID.
 * @param serverName
 *   Human-readable server name.
 * @param version
 *   NATS server version string.
 * @param host
 *   Server hostname.
 * @param port
 *   Server port.
 * @param maxPayload
 *   Maximum allowed payload size in bytes.
 * @param clientId
 *   The client ID assigned by the server.
 */
final case class NatsServerInfo(
  serverId: String,
  serverName: String,
  version: String,
  host: String,
  port: Int,
  maxPayload: Long,
  clientId: Long
)

object NatsServerInfo {

  private[nats] def fromJava(info: io.nats.client.api.ServerInfo): NatsServerInfo =
    NatsServerInfo(
      serverId = Option(info.getServerId).getOrElse(""),
      serverName = Option(info.getServerName).getOrElse(""),
      version = Option(info.getVersion).getOrElse(""),
      host = Option(info.getHost).getOrElse(""),
      port = info.getPort,
      maxPayload = info.getMaxPayload,
      clientId = info.getClientId
    )
}
