package zio.nats.config

import io.nats.client.Options
import zio.*

/**
 * Configuration for a NATS connection.
 *
 * Provides a Scala-friendly API over `io.nats.client.Options.Builder`.
 *
 * === Authentication ===
 *
 * Set the `auth` field to one of the [[NatsAuth]] variants. Only one
 * authentication method can be active at a time; impossible combinations
 * (e.g. token and user/password simultaneously) cannot be constructed.
 * Use [[NatsAuth.Custom]] for dynamic credential rotation via a programmatic
 * `io.nats.client.AuthHandler`.
 *
 * === TLS ===
 *
 * Set the `tls` field to one of the [[NatsTls]] variants. Use
 * [[NatsTls.Custom]] for a pre-built `javax.net.ssl.SSLContext` when
 * certificates are loaded at runtime (e.g. from a secrets manager).
 *
 * @param servers
 *   List of NATS server URLs (default: `nats://localhost:4222`).
 * @param connectionName
 *   Optional client name shown in server logs and monitoring.
 * @param connectionTimeout
 *   Maximum time to wait for the initial connection (default: 2s).
 * @param reconnectWait
 *   Base delay between reconnect attempts (default: 2s).
 * @param maxReconnects
 *   Maximum reconnect attempts before giving up (default: 60; -1 = unlimited).
 * @param pingInterval
 *   Interval between client-initiated PING messages (default: 2m).
 * @param requestCleanupInterval
 *   How often stale request inboxes are swept (default: 5s).
 * @param bufferSize
 *   Size of the outbound message buffer in bytes (default: 64 KiB).
 * @param noEcho
 *   If true, messages published by this connection are not echoed back to its
 *   own subscriptions (default: false).
 * @param utf8Support
 *   Enable UTF-8 subject support (default: false).
 * @param inboxPrefix
 *   Prefix for auto-generated reply-to inboxes (default: `_INBOX.`).
 * @param auth
 *   Authentication method (default: [[NatsAuth.NoAuth]]). Use [[NatsAuth.Token]],
 *   [[NatsAuth.UserPassword]], or [[NatsAuth.CredentialFile]] for authenticated
 *   connections.
 * @param tls
 *   TLS configuration (default: [[NatsTls.Disabled]]). Use [[NatsTls.SystemDefault]]
 *   for public-CA-signed servers, or [[NatsTls.KeyStore]] for mTLS.
 * @param reconnectJitter
 *   Random jitter added to [[reconnectWait]] to avoid thundering herd on reconnect
 *   (default: 100ms).
 * @param reconnectJitterTls
 *   Jitter for TLS connections, which are more expensive to establish (default: 1s).
 * @param reconnectBufferSize
 *   Maximum bytes to buffer for in-flight publishes during a reconnect window
 *   (default: 8 MiB). Set to 0 to disable buffering.
 * @param maxMessagesInOutgoingQueue
 *   Maximum number of messages in the outbound queue (default: 0 = unlimited).
 * @param discardMessagesWhenOutgoingQueueFull
 *   If true, new messages are discarded when the outbound queue is full instead
 *   of blocking (default: false).
 * @param writeQueuePushTimeout
 *   How long to wait when the outbound queue is full before giving up
 *   (default: [[Duration.Zero]] = block indefinitely).
 * @param socketWriteTimeout
 *   Socket-level write timeout to prevent hanging on a dead connection
 *   (default: [[Duration.Zero]] = no timeout).
 * @param socketReadTimeout
 *   Socket-level read timeout in milliseconds (default: 0 = no timeout).
 * @param maxControlLine
 *   Maximum length of a NATS protocol control line in bytes; must match the
 *   server's `max_control_line` setting (default: 0 = jnats default).
 * @param maxPingsOut
 *   Maximum number of client PINGs in-flight before the connection is considered
 *   stale and closed (default: 2).
 * @param drainTimeout
 *   Maximum time to wait for subscriptions to drain when the connection's ZLayer
 *   scope ends (default: 30s).
 */
final case class NatsConfig(
  servers: List[String] = List("nats://localhost:4222"),
  connectionName: Option[String] = None,
  connectionTimeout: Duration = 2.seconds,
  reconnectWait: Duration = 2.seconds,
  maxReconnects: Int = 60,
  pingInterval: Duration = 2.minutes,
  requestCleanupInterval: Duration = 5.seconds,
  bufferSize: Int = 64 * 1024,
  noEcho: Boolean = false,
  utf8Support: Boolean = false,
  inboxPrefix: String = "_INBOX.",
  auth: NatsAuth = NatsAuth.NoAuth,
  tls: NatsTls = NatsTls.Disabled,
  reconnectJitter: Duration = 100.millis,
  reconnectJitterTls: Duration = 1.second,
  reconnectBufferSize: Long = 8L * 1024 * 1024,
  maxMessagesInOutgoingQueue: Int = 0,
  discardMessagesWhenOutgoingQueueFull: Boolean = false,
  writeQueuePushTimeout: Duration = Duration.Zero,
  socketWriteTimeout: Duration = Duration.Zero,
  socketReadTimeout: Int = 0,
  maxControlLine: Int = 0,
  maxPingsOut: Int = 2,
  drainTimeout: Duration = 30.seconds
) {

  /** Build jnats Options from this config. */
  private[nats] def toOptions: Options = toOptionsBuilder.build()

  /** Build a jnats `Options.Builder` from this config. */
  private[nats] def toOptionsBuilder: Options.Builder = {
    var builder = new Options.Builder()
    servers.foreach(s => builder = builder.server(s))
    connectionName.foreach(n => builder = builder.connectionName(n))
    builder = builder.connectionTimeout(connectionTimeout.asJava)
    builder = builder.reconnectWait(reconnectWait.asJava)
    builder = builder.maxReconnects(maxReconnects)
    builder = builder.pingInterval(pingInterval.asJava)
    builder = builder.requestCleanupInterval(requestCleanupInterval.asJava)
    builder = builder.bufferSize(bufferSize)
    if (noEcho) builder = builder.noEcho()
    if (utf8Support) builder = builder.supportUTF8Subjects()
    builder = builder.inboxPrefix(inboxPrefix)
    builder = auth.applyTo(builder)
    builder = tls.applyTo(builder)
    builder = builder.reconnectJitter(reconnectJitter.asJava)
    builder = builder.reconnectJitterTls(reconnectJitterTls.asJava)
    builder = builder.reconnectBufferSize(reconnectBufferSize)
    if (maxMessagesInOutgoingQueue > 0) builder = builder.maxMessagesInOutgoingQueue(maxMessagesInOutgoingQueue)
    if (discardMessagesWhenOutgoingQueueFull) builder = builder.discardMessagesWhenOutgoingQueueFull()
    if (writeQueuePushTimeout > Duration.Zero) builder = builder.writeQueuePushTimeout(writeQueuePushTimeout.asJava)
    if (socketWriteTimeout > Duration.Zero) builder = builder.socketWriteTimeout(socketWriteTimeout.asJava)
    if (socketReadTimeout > 0) builder = builder.socketReadTimeoutMillis(socketReadTimeout)
    if (maxControlLine > 0) builder = builder.maxControlLine(maxControlLine)
    builder = builder.maxPingsOut(maxPingsOut)
    builder
  }
}

object NatsConfig {

  /** Default config connecting to `nats://localhost:4222` with no auth and no TLS. */
  val default: NatsConfig = NatsConfig()

  /** Config for a single server URL with all other settings at their defaults. */
  def apply(server: String): NatsConfig = NatsConfig(servers = List(server))

  /** ZLayer providing a default [[NatsConfig]] connecting to `nats://localhost:4222`. */
  val live: ULayer[NatsConfig] = ZLayer.succeed(default)
}
