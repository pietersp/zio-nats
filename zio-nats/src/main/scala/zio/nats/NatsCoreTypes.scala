package zio.nats

import io.nats.client.{Connection => JConnection}
import zio.Chunk

import scala.jdk.CollectionConverters._

/**
 * An opaque type alias for NATS subject strings.
 *
 * Using an opaque type ensures subjects are not accidentally mixed with
 * arbitrary strings at compile time, while incurring zero runtime overhead.
 */
opaque type Subject = String

object Subject {

  /** Create a Subject from a raw string (no validation). */
  def apply(s: String): Subject = s

  /**
   * Parse a subject string, returning Left if it is null or blank.
   *
   * Use this for user-supplied input that may be invalid.
   */
  def parse(s: String): Either[String, Subject] =
    if (s == null || s.isBlank) Left(s"Invalid subject: '$s'")
    else Right(s)

  /**
   * Create a Subject without validation.
   *
   * Prefer [[parse]] for untrusted input. Use this only when the value is
   * statically known to be valid (e.g. constants).
   */
  def unsafe(s: String): Subject = s

  extension (s: Subject) def value: String = s
}

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
// KeyValueOperation
// ---------------------------------------------------------------------------

enum KeyValueOperation { case Put, Delete, Purge }

object KeyValueOperation {
  private[nats] def fromJava(op: io.nats.client.api.KeyValueOperation): KeyValueOperation = op match {
    case io.nats.client.api.KeyValueOperation.PUT   => Put
    case io.nats.client.api.KeyValueOperation.DELETE => Delete
    case io.nats.client.api.KeyValueOperation.PURGE => Purge
  }
}

// ---------------------------------------------------------------------------
// StorageType
// ---------------------------------------------------------------------------

enum StorageType {
  case File, Memory

  private[nats] def toJava: io.nats.client.api.StorageType = this match {
    case File   => io.nats.client.api.StorageType.File
    case Memory => io.nats.client.api.StorageType.Memory
  }
}

object StorageType {
  private[nats] def fromJava(st: io.nats.client.api.StorageType): StorageType = st match {
    case io.nats.client.api.StorageType.File   => File
    case io.nats.client.api.StorageType.Memory => Memory
  }
}

// ---------------------------------------------------------------------------
// ObjectMeta
// ---------------------------------------------------------------------------

final case class ObjectMeta(
  name: String,
  description: Option[String] = None,
  headers: Headers = Headers.empty
) {
  private[nats] def toJava: io.nats.client.api.ObjectMeta = {
    val b = io.nats.client.api.ObjectMeta.builder(name)
    description.foreach(b.description)
    if (headers.nonEmpty) {
      val jHeaders = new io.nats.client.impl.Headers()
      headers.values.foreach { case (key, values) =>
        jHeaders.add(key, values.toList.asJava)
      }
      b.headers(jHeaders)
    }
    b.build()
  }
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
enum ConnectionStatus {

  /** The connection is fully established and ready for use. */
  case Connected

  /** Performing the initial connection handshake. */
  case Connecting

  /** Attempting to reconnect after a disconnection. */
  case Reconnecting

  /** The connection has been dropped and is not reconnecting. */
  case Disconnected

  /** The connection has been permanently closed. */
  case Closed
}

object ConnectionStatus {
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
