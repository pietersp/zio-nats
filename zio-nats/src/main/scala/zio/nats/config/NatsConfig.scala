package zio.nats.config

import io.nats.client.{AuthHandler, Options}
import zio.*

/**
 * Configuration for a NATS connection.
 *
 * Provides a Scala-friendly API over io.nats.client.Options.Builder. For
 * advanced configuration not covered by this case class, use
 * `optionsCustomizer` to access the raw builder directly.
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
  credentialPath: Option[String] = None,
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
    credentialPath.foreach(p => builder = builder.credentialPath(p))
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
