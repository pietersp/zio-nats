package zio.nats.config

import io.nats.client.Options
import zio.*
import java.nio.file.Paths

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

  // ---------------------------------------------------------------------------
  // ZIO Config descriptors
  // ---------------------------------------------------------------------------

  // Private case classes used to group config fields before combining them.
  // Using case classes (not tuples) ensures Zippable does not try to flatten
  // their fields into the outer zip chain.
  private final case class ConnGroup(
    servers: List[String], connectionName: Option[String], connectionTimeout: Duration,
    reconnectWait: Duration, maxReconnects: Int, pingInterval: Duration,
    requestCleanupInterval: Duration, bufferSize: Int, noEcho: Boolean,
    utf8Support: Boolean, inboxPrefix: String
  )
  private final case class TunGroup(
    reconnectJitter: Duration, reconnectJitterTls: Duration, reconnectBufferSize: Long,
    maxMessagesInOutgoingQueue: Int, discardMessagesWhenOutgoingQueueFull: Boolean,
    writeQueuePushTimeout: Duration, socketWriteTimeout: Duration, socketReadTimeout: Int,
    maxControlLine: Int, maxPingsOut: Int, drainTimeout: Duration
  )

  // Auth: each variant validates the type field and reads its required sub-fields.
  // Variants are chained with orElse; a final Config.succeed provides the default
  // when the auth section is absent entirely.
  // Note: an unrecognised type value (e.g. "custom") also falls back to NoAuth.
  private val authConfig: Config[NatsAuth] = {
    val noAuth: Config[NatsAuth] =
      Config.string("type").nested("auth")
        .validate("auth.type must be 'no-auth'")(_.trim == "no-auth")
        .map(_ => NatsAuth.NoAuth)

    val token: Config[NatsAuth] =
      Config.string("type").nested("auth")
        .validate("auth.type must be 'token'")(_.trim == "token")
        .zip(Config.string("value").nested("auth"))
        .map { case (_, v) => NatsAuth.Token(v) }

    val userPassword: Config[NatsAuth] =
      Config.string("type").nested("auth")
        .validate("auth.type must be 'user-password'")(_.trim == "user-password")
        .zip(Config.string("username").nested("auth") zip Config.string("password").nested("auth"))
        .map { case (_, (u, p)) => NatsAuth.UserPassword(u, p) }

    val credentialFile: Config[NatsAuth] =
      Config.string("type").nested("auth")
        .validate("auth.type must be 'credential-file'")(_.trim == "credential-file")
        .zip(Config.string("path").nested("auth"))
        .map { case (_, p) => NatsAuth.CredentialFile(Paths.get(p)) }

    noAuth
      .orElse(token)
      .orElse(userPassword)
      .orElse(credentialFile)
      .orElse(Config.succeed(NatsAuth.NoAuth))
  }

  // TLS: same orElse + validate pattern as authConfig.
  private val tlsConfig: Config[NatsTls] = {
    val disabled: Config[NatsTls] =
      Config.string("type").nested("tls")
        .validate("tls.type must be 'disabled'")(_.trim == "disabled")
        .map(_ => NatsTls.Disabled)

    val systemDefault: Config[NatsTls] =
      Config.string("type").nested("tls")
        .validate("tls.type must be 'system-default'")(_.trim == "system-default")
        .map(_ => NatsTls.SystemDefault)

    val keyStore: Config[NatsTls] = {
      // Config.zip uses Zippable which produces flat tuples, not nested pairs.
      type KsFields = (String, String, Option[String], Option[String], Option[String], Boolean)
      val ksConfig: Config[KsFields] =
        Config.string("key-store-path").nested("tls") zip
        Config.string("key-store-password").nested("tls") zip
        Config.string("trust-store-path").optional.nested("tls") zip
        Config.string("trust-store-password").optional.nested("tls") zip
        Config.string("algorithm").optional.nested("tls") zip
        Config.boolean("tls-first").withDefault(false).nested("tls")
      Config.string("type").nested("tls")
        .validate("tls.type must be 'key-store'")(_.trim == "key-store")
        .zip(ksConfig)
        .map { case (_, (ksp, kspw, tsp, tspw, alg, first)) =>
          NatsTls.KeyStore(Paths.get(ksp), kspw, tsp.map(Paths.get(_)), tspw, alg, first)
        }
    }

    disabled
      .orElse(systemDefault)
      .orElse(keyStore)
      .orElse(Config.succeed(NatsTls.Disabled))
  }

  /**
   * ZIO [[Config]] descriptor for [[NatsConfig]].
   *
   * All fields default to their [[NatsConfig]] case class defaults — only values
   * you want to override need to be present. Duration fields use ISO-8601 format
   * (e.g. `PT5S` for 5 seconds, `PT2M` for 2 minutes). The `servers` field uses
   * indexed keys (`servers.0`, `servers.1`, ...).
   *
   * `NatsAuth.Custom` and `NatsTls.Custom` are not reachable via text config —
   * construct [[NatsConfig]] programmatically when you need those variants.
   *
   * This descriptor is flat (no nesting). Use [[fromConfig]] for the conventional
   * `nats`-nested layer, or apply your own nesting:
   * {{{
   *   ZIO.config(NatsConfig.config.nested("myapp").nested("nats"))
   * }}}
   */
  val config: Config[NatsConfig] = {
    // Each group is mapped to a private case class so Zippable never tries to
    // flatten the fields across groups.  The final zip is a clean 4-element tuple.
    val connGroupConfig: Config[ConnGroup] =
      (Config.listOf("servers", Config.string).withDefault(List("nats://localhost:4222")) zip
       Config.string("connection-name").optional zip
       Config.duration("connection-timeout").withDefault(2.seconds) zip
       Config.duration("reconnect-wait").withDefault(2.seconds) zip
       Config.int("max-reconnects").withDefault(60) zip
       Config.duration("ping-interval").withDefault(2.minutes) zip
       Config.duration("request-cleanup-interval").withDefault(5.seconds) zip
       Config.int("buffer-size").withDefault(64 * 1024) zip
       Config.boolean("no-echo").withDefault(false) zip
       Config.boolean("utf8-support").withDefault(false) zip
       Config.string("inbox-prefix").withDefault("_INBOX.")
      ).map { case (servers, connectionName, connectionTimeout, reconnectWait, maxReconnects,
                    pingInterval, requestCleanupInterval, bufferSize, noEcho, utf8Support,
                    inboxPrefix) =>
        ConnGroup(servers, connectionName, connectionTimeout, reconnectWait, maxReconnects,
                  pingInterval, requestCleanupInterval, bufferSize, noEcho, utf8Support, inboxPrefix)
      }

    val tunGroupConfig: Config[TunGroup] =
      (Config.duration("reconnect-jitter").withDefault(100.millis) zip
       Config.duration("reconnect-jitter-tls").withDefault(1.second) zip
       Config.long("reconnect-buffer-size").withDefault(8L * 1024 * 1024) zip
       Config.int("max-messages-in-outgoing-queue").withDefault(0) zip
       Config.boolean("discard-messages-when-outgoing-queue-full").withDefault(false) zip
       Config.duration("write-queue-push-timeout").withDefault(Duration.Zero) zip
       Config.duration("socket-write-timeout").withDefault(Duration.Zero) zip
       Config.int("socket-read-timeout").withDefault(0) zip // milliseconds
       Config.int("max-control-line").withDefault(0) zip
       Config.int("max-pings-out").withDefault(2) zip
       Config.duration("drain-timeout").withDefault(30.seconds)
      ).map { case (reconnectJitter, reconnectJitterTls, reconnectBufferSize,
                    maxMessagesInOutgoingQueue, discardMessagesWhenOutgoingQueueFull,
                    writeQueuePushTimeout, socketWriteTimeout, socketReadTimeout,
                    maxControlLine, maxPingsOut, drainTimeout) =>
        TunGroup(reconnectJitter, reconnectJitterTls, reconnectBufferSize,
                 maxMessagesInOutgoingQueue, discardMessagesWhenOutgoingQueueFull,
                 writeQueuePushTimeout, socketWriteTimeout, socketReadTimeout,
                 maxControlLine, maxPingsOut, drainTimeout)
      }

    (connGroupConfig zip tunGroupConfig zip authConfig zip tlsConfig).map {
      case (conn, tun, auth, tls) =>
        NatsConfig(
          servers                              = conn.servers,
          connectionName                       = conn.connectionName,
          connectionTimeout                    = conn.connectionTimeout,
          reconnectWait                        = conn.reconnectWait,
          maxReconnects                        = conn.maxReconnects,
          pingInterval                         = conn.pingInterval,
          requestCleanupInterval               = conn.requestCleanupInterval,
          bufferSize                           = conn.bufferSize,
          noEcho                               = conn.noEcho,
          utf8Support                          = conn.utf8Support,
          inboxPrefix                          = conn.inboxPrefix,
          auth                                 = auth,
          tls                                  = tls,
          reconnectJitter                      = tun.reconnectJitter,
          reconnectJitterTls                   = tun.reconnectJitterTls,
          reconnectBufferSize                  = tun.reconnectBufferSize,
          maxMessagesInOutgoingQueue           = tun.maxMessagesInOutgoingQueue,
          discardMessagesWhenOutgoingQueueFull = tun.discardMessagesWhenOutgoingQueueFull,
          writeQueuePushTimeout                = tun.writeQueuePushTimeout,
          socketWriteTimeout                   = tun.socketWriteTimeout,
          socketReadTimeout                    = tun.socketReadTimeout,
          maxControlLine                       = tun.maxControlLine,
          maxPingsOut                          = tun.maxPingsOut,
          drainTimeout                         = tun.drainTimeout
        )
    }
  }

  /**
   * ZLayer that loads [[NatsConfig]] from the environment via ZIO Config,
   * reading from the `nats` key namespace.
   *
   * With the default [[zio.ConfigProvider]] (env vars + system properties),
   * keys are normalised to `UPPER_SNAKE_CASE` under the `NATS_` prefix:
   * {{{
   *   NATS_SERVERS_0=nats://broker:4222
   *   NATS_AUTH_TYPE=token
   *   NATS_AUTH_VALUE=s3cr3t
   *   NATS_CONNECTION_TIMEOUT=PT5S
   * }}}
   *
   * All fields have defaults; only values you want to override need to be set.
   *
   * For HOCON loading, add `dev.zio::zio-config-typesafe` and provide
   * `Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())`.
   *
   * For custom nesting use [[config]] directly:
   * {{{
   *   ZIO.config(NatsConfig.config.nested("myapp").nested("nats"))
   * }}}
   */
  val fromConfig: ZLayer[Any, Config.Error, NatsConfig] =
    ZLayer.fromZIO(ZIO.config(config.nested("nats")))
}
