package zio.nats.config

import io.nats.client.{AuthHandler, Options}
import zio.*

import java.nio.file.Path
import javax.net.ssl.SSLContext

/**
 * Configuration for a NATS connection.
 *
 * Provides a Scala-friendly API over `io.nats.client.Options.Builder`. For
 * advanced configuration not covered by this case class, use
 * `advancedOptionsCustomizer` to access the raw builder directly.
 *
 * @param servers
 *   List of NATS server URLs (default: `nats://localhost:4222`).
 * @param connectionName
 *   Optional client name shown in server logs and monitoring.
 * @param connectionTimeout
 *   Maximum time to wait for the initial connection (default: 2s).
 * @param reconnectWait
 *   Delay between reconnect attempts (default: 2s).
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
 * @param authHandler
 *   Custom authentication handler (NKey, JWT, etc.).
 * @param token
 *   Authentication token.
 * @param username
 *   Username for user/password authentication.
 * @param password
 *   Password for user/password authentication.
 * @param credentialPath
 *   Path to a `.creds` file for NKey/JWT authentication. Validated at
 *   construction (file must exist on the filesystem).
 * @param tlsContext
 *   Custom [[SSLContext]] for TLS/mTLS connections. When set, the connection
 *   will use TLS using the provided context. Use the standard
 *   `javax.net.ssl.SSLContext` API to configure certificates, trust stores, and
 *   client keys without any jnats imports.
 * @param tlsFirst
 *   If true, initiate TLS immediately on connect rather than waiting for a
 *   server upgrade (`tls_required` servers). Equivalent to jnats
 *   `Options.Builder.tlsFirst()` (default: false).
 * @param optionsCustomizer
 *   Escape hatch: apply any additional `Options.Builder` settings not covered
 *   by the fields above.
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
  authHandler: Option[AuthHandler] = None,
  token: Option[String] = None,
  username: Option[String] = None,
  password: Option[String] = None,
  credentialPath: Option[Path] = None,
  tlsContext: Option[SSLContext] = None,
  tlsFirst: Boolean = false,
  optionsCustomizer: Options.Builder => Options.Builder = identity
) {

  /** Build jnats Options from this config. */
  private[nats] def toOptions: Options = toOptionsBuilder.build()

  /**
   * Build jnats Options.Builder from this config (allows further
   * customization).
   */
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
    authHandler.foreach(h => builder = builder.authHandler(h))
    token.foreach(t => builder = builder.token(t.toCharArray))
    for {
      u <- username
      p <- password
    } builder = builder.userInfo(u.toCharArray, p.toCharArray)
    credentialPath.foreach(p => builder = builder.credentialPath(p.toString))
    tlsContext.foreach(ctx => builder = builder.sslContext(ctx))
    if (tlsFirst) builder = builder.tlsFirst()
    optionsCustomizer(builder)
  }
}

object NatsConfig {

  /** Default config connecting to localhost:4222. */
  val default: NatsConfig = NatsConfig()

  /** Config from a single server URL. */
  def apply(server: String): NatsConfig = NatsConfig(servers = List(server))

  /** ZLayer providing a default config. */
  val live: ULayer[NatsConfig] = ZLayer.succeed(default)
}
