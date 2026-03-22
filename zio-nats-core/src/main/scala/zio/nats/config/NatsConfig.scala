package zio.nats.config

import io.nats.client.Options
import zio.*
import java.net.URI
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
 *   Socket-level read timeout to prevent hanging on a dead connection
 *   (default: [[Duration.Zero]] = no timeout).
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
  socketReadTimeout: Duration = Duration.Zero,
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
    // jnats exposes socketReadTimeout only via an int-millis overload (no Duration variant),
    // unlike socketWriteTimeout / writeQueuePushTimeout which have java.time.Duration overloads.
    // The Long→Int cast is safe for any realistic timeout value (Int.MaxValue ≈ 24.8 days).
    if (socketReadTimeout > Duration.Zero) builder = builder.socketReadTimeoutMillis(socketReadTimeout.toMillis.toInt)
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
    utf8Support: Boolean, inboxPrefix: String, drainTimeout: Duration
  )
  private final case class TunGroup(
    reconnectJitter: Duration, reconnectJitterTls: Duration, reconnectBufferSize: Long,
    maxMessagesInOutgoingQueue: Int, discardMessagesWhenOutgoingQueueFull: Boolean,
    writeQueuePushTimeout: Duration, socketWriteTimeout: Duration, socketReadTimeout: Duration,
    maxControlLine: Int, maxPingsOut: Int
  )

  // Validates that a server URL is a parseable URI with a non-empty host.
  // Called per-element from Config.listOf so each bad URL is reported individually.
  private def validateNatsUrl(url: String): Either[Config.Error, String] =
    try {
      val uri = new URI(url)
      if (uri.getScheme == null || uri.getHost == null || uri.getHost.isEmpty)
        Left(Config.Error.InvalidData(Chunk.empty,
          s"'$url' is not a valid NATS server URL — expected e.g. nats://host:4222 or tls://host:4222"))
      else
        Right(url)
    } catch {
      case _: java.net.URISyntaxException =>
        Left(Config.Error.InvalidData(Chunk.empty,
          s"'$url' is not a valid NATS server URL — expected e.g. nats://host:4222 or tls://host:4222"))
    }

  // Auth: all sub-fields are read as optional strings so that mapOrFail can dispatch
  // on the type value and surface a single, targeted error message — one error for an
  // unknown type, one for a missing required sub-field.
  // NatsAuth.configTypeKey is the compile-time exhaustiveness guard: it forces a
  // non-exhaustive match error when a new NatsAuth case is added without updating it,
  // signalling that the pattern arms below also need updating.
  // NatsAuth.Keys is the single source of truth for all type key strings.
  private val authConfig: Config[NatsAuth] =
    (Config.string("type").optional.nested("auth") zip
     Config.string("value").optional.nested("auth") zip
     Config.string("username").optional.nested("auth") zip
     Config.string("password").optional.nested("auth") zip
     Config.string("path").optional.nested("auth")
    ).mapOrFail {
      case (None,                              _,       _,       _,       _      ) => Right(NatsAuth.NoAuth)
      case (Some(NatsAuth.Keys.noAuth),        _,       _,       _,       _      ) => Right(NatsAuth.NoAuth)
      case (Some(NatsAuth.Keys.token),         Some(v), _,       _,       _      ) => Right(NatsAuth.Token(v))
      case (Some(NatsAuth.Keys.token),         None,    _,       _,       _      ) =>
        Left(Config.Error.MissingData(Chunk.empty,
          s"auth.value is required when auth.type = ${NatsAuth.Keys.token}"))
      case (Some(NatsAuth.Keys.userPassword),  _,       Some(u), Some(p), _      ) => Right(NatsAuth.UserPassword(u, p))
      case (Some(NatsAuth.Keys.userPassword),  _,       _,       _,       _      ) =>
        Left(Config.Error.MissingData(Chunk.empty,
          s"auth.username and auth.password are required when auth.type = ${NatsAuth.Keys.userPassword}"))
      case (Some(NatsAuth.Keys.credentialFile),_,       _,       _,       Some(p)) => Right(NatsAuth.CredentialFile(Paths.get(p)))
      case (Some(NatsAuth.Keys.credentialFile),_,       _,       _,       None   ) =>
        Left(Config.Error.MissingData(Chunk.empty,
          s"auth.path is required when auth.type = ${NatsAuth.Keys.credentialFile}"))
      case (Some(t),                           _,       _,       _,       _      ) =>
        Left(Config.Error.InvalidData(Chunk.empty,
          s"Unknown auth.type '$t'. Valid values: ${NatsAuth.Keys.noAuth}, ${NatsAuth.Keys.token}, " +
          s"${NatsAuth.Keys.userPassword}, ${NatsAuth.Keys.credentialFile}. " +
          "NatsAuth.Custom requires programmatic NatsConfig construction."))
    }

  // TLS: same mapOrFail approach as authConfig.
  // NatsTls.configTypeKey and NatsTls.Keys serve the same role as for auth above.
  private val tlsConfig: Config[NatsTls] =
    (Config.string("type").optional.nested("tls") zip
     Config.string("key-store-path").optional.nested("tls") zip
     Config.string("key-store-password").optional.nested("tls") zip
     Config.string("trust-store-path").optional.nested("tls") zip
     Config.string("trust-store-password").optional.nested("tls") zip
     Config.string("algorithm").optional.nested("tls") zip
     Config.boolean("tls-first").withDefault(false).nested("tls")
    ).mapOrFail {
      case (None,                           _, _, _, _, _, _) => Right(NatsTls.Disabled)
      case (Some(NatsTls.Keys.disabled),    _, _, _, _, _, _) => Right(NatsTls.Disabled)
      case (Some(NatsTls.Keys.systemDefault), _, _, _, _, _, _) => Right(NatsTls.SystemDefault)
      case (Some(NatsTls.Keys.keyStore), Some(ksp), Some(kspw), tsp, tspw, alg, first) =>
        Right(NatsTls.KeyStore(Paths.get(ksp), kspw, tsp.map(Paths.get(_)), tspw, alg, first))
      case (Some(NatsTls.Keys.keyStore), ksp, kspw, _, _, _, _) =>
        val missing = List(
          Option.when(ksp.isEmpty)("tls.key-store-path"),
          Option.when(kspw.isEmpty)("tls.key-store-password")
        ).flatten
        Left(Config.Error.MissingData(Chunk.empty,
          s"${missing.mkString(" and ")} ${if (missing.size > 1) "are" else "is"} required when tls.type = ${NatsTls.Keys.keyStore}"))
      case (Some(t), _, _, _, _, _, _) =>
        Left(Config.Error.InvalidData(Chunk.empty,
          s"Unknown tls.type '$t'. Valid values: ${NatsTls.Keys.disabled}, ${NatsTls.Keys.systemDefault}, " +
          s"${NatsTls.Keys.keyStore}. NatsTls.Custom requires programmatic NatsConfig construction."))
    }

  /**
   * ZIO [[Config]] descriptor for [[NatsConfig]].
   *
   * All fields default to their [[NatsConfig]] case class defaults — only values
   * you want to override need to be present. All duration and timeout fields use
   * ISO-8601 format (e.g. `PT5S` for 5 seconds, `PT2M` for 2 minutes,
   * `PT0.5S` for 500 ms). The `servers` list uses the config provider's sequence
   * delimiter (comma by default for env vars, native list syntax for HOCON).
   * Each server URL is validated at load time (must be a parseable URI with a
   * non-empty host); invalid URLs produce a [[Config.Error.InvalidData]] before
   * any connection is attempted.
   *
   * `NatsAuth.Custom` and `NatsTls.Custom` are not reachable via text config —
   * construct [[NatsConfig]] programmatically when you need those variants.
   *
   * This descriptor reads keys at the root level — there is no `nats.` prefix.
   * Use [[fromConfig]] for the conventional `nats`-prefixed layer.
   * For a different prefix, apply your own nesting — note that `nested` calls
   * stack from inner to outer (last call = outermost namespace):
   * {{{
   *   // Config lives at myapp.nats.* (e.g. HOCON: myapp { nats { servers = [...] } })
   *   ZIO.config(NatsConfig.config.nested("nats").nested("myapp"))
   * }}}
   */
  val config: Config[NatsConfig] = {
    // Each group is mapped to a private case class so Zippable never tries to
    // flatten the fields across groups.  The final zip is a clean 4-element tuple.
    val connGroupConfig: Config[ConnGroup] =
      (Config.listOf("servers", Config.string.mapOrFail(validateNatsUrl))
         .withDefault(List("nats://localhost:4222")) zip
       Config.string("connection-name").optional zip
       Config.duration("connection-timeout").withDefault(2.seconds) zip
       Config.duration("reconnect-wait").withDefault(2.seconds) zip
       Config.int("max-reconnects").withDefault(60) zip
       Config.duration("ping-interval").withDefault(2.minutes) zip
       Config.duration("request-cleanup-interval").withDefault(5.seconds) zip
       Config.int("buffer-size").withDefault(64 * 1024) zip
       Config.boolean("no-echo").withDefault(false) zip
       Config.boolean("utf8-support").withDefault(false) zip
       Config.string("inbox-prefix").withDefault("_INBOX.") zip
       Config.duration("drain-timeout").withDefault(30.seconds)
      ).map { case (servers, connectionName, connectionTimeout, reconnectWait, maxReconnects,
                    pingInterval, requestCleanupInterval, bufferSize, noEcho, utf8Support,
                    inboxPrefix, drainTimeout) =>
        ConnGroup(servers, connectionName, connectionTimeout, reconnectWait, maxReconnects,
                  pingInterval, requestCleanupInterval, bufferSize, noEcho, utf8Support,
                  inboxPrefix, drainTimeout)
      }

    val tunGroupConfig: Config[TunGroup] =
      (Config.duration("reconnect-jitter").withDefault(100.millis) zip
       Config.duration("reconnect-jitter-tls").withDefault(1.second) zip
       Config.long("reconnect-buffer-size").withDefault(8L * 1024 * 1024) zip
       Config.int("max-messages-in-outgoing-queue").withDefault(0) zip
       Config.boolean("discard-messages-when-outgoing-queue-full").withDefault(false) zip
       Config.duration("write-queue-push-timeout").withDefault(Duration.Zero) zip
       Config.duration("socket-write-timeout").withDefault(Duration.Zero) zip
       Config.duration("socket-read-timeout").withDefault(Duration.Zero) zip
       Config.int("max-control-line").withDefault(0) zip
       Config.int("max-pings-out").withDefault(2)
      ).map { case (reconnectJitter, reconnectJitterTls, reconnectBufferSize,
                    maxMessagesInOutgoingQueue, discardMessagesWhenOutgoingQueueFull,
                    writeQueuePushTimeout, socketWriteTimeout, socketReadTimeout,
                    maxControlLine, maxPingsOut) =>
        TunGroup(reconnectJitter, reconnectJitterTls, reconnectBufferSize,
                 maxMessagesInOutgoingQueue, discardMessagesWhenOutgoingQueueFull,
                 writeQueuePushTimeout, socketWriteTimeout, socketReadTimeout,
                 maxControlLine, maxPingsOut)
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
          drainTimeout                         = conn.drainTimeout
        )
    }
  }

  /**
   * ZLayer that loads [[NatsConfig]] from the environment via ZIO Config,
   * reading from the `nats` key namespace.
   *
   * With the default [[zio.ConfigProvider]] (env vars + system properties),
   * keys are normalised to `UPPER_SNAKE_CASE` under the `NATS_` prefix.
   * List values use comma as the sequence delimiter:
   * {{{
   *   NATS_SERVERS=nats://broker:4222
   *   NATS_AUTH_TYPE=token
   *   NATS_AUTH_VALUE=s3cr3t
   *   NATS_CONNECTION_TIMEOUT=PT5S
   *   NATS_SOCKET_READ_TIMEOUT=PT0.5S
   * }}}
   *
   * All fields have defaults; only values you want to override need to be set.
   *
   * For HOCON loading, add `dev.zio::zio-config-typesafe` and provide
   * `Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())`.
   *
   * For custom nesting use [[config]] directly — `nested` calls stack from inner
   * to outer, so the last call becomes the outermost namespace:
   * {{{
   *   // Config lives at myapp.nats.* (e.g. HOCON: myapp { nats { servers = [...] } })
   *   ZIO.config(NatsConfig.config.nested("nats").nested("myapp"))
   * }}}
   */
  val fromConfig: ZLayer[Any, Config.Error, NatsConfig] =
    ZLayer.fromZIO(ZIO.config(config.nested("nats")))
}
