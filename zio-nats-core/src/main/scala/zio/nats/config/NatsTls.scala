package zio.nats.config

import io.nats.client.Options
import java.nio.file.Path
import javax.net.ssl.SSLContext

/**
 * TLS configuration for a NATS connection.
 *
 * `Disabled` and `SystemDefault` require no files and are fully
 * text-configurable. `KeyStore` covers standard TLS and mTLS via JVM
 * keystore/truststore files and is also fully text-configurable via
 * [[zio.nats.config.NatsConfig]]. `Custom` accepts a pre-built
 * [[javax.net.ssl.SSLContext]] for certificates loaded at runtime (e.g. from a
 * secrets manager).
 *
 * {{{
 *   // No TLS (default)
 *   NatsConfig()
 *
 *   // TLS using the JVM's system trust store (no client certificate)
 *   NatsConfig(tls = NatsTls.SystemDefault)
 *
 *   // mTLS with client keystore and custom truststore
 *   NatsConfig(tls = NatsTls.KeyStore(
 *     keyStorePath       = Paths.get("/certs/client.jks"),
 *     keyStorePassword   = "changeit",
 *     trustStorePath     = Some(Paths.get("/certs/truststore.jks")),
 *     trustStorePassword = Some("changeit")
 *   ))
 *
 *   // Runtime SSLContext (e.g. certificates fetched from Vault at startup)
 *   NatsConfig(tls = NatsTls.Custom(mySSLContext))
 * }}}
 */
enum NatsTls:
  /** No TLS — plaintext connection. */
  case Disabled

  /**
   * TLS using the JVM's default SSL context (system trust store, no client
   * certificate). Suitable for servers with certificates signed by a public CA.
   */
  case SystemDefault

  /**
   * TLS via JVM keystore and/or truststore files.
   *
   * @param keyStorePath
   *   Path to the client keystore (JKS or PKCS12). Required.
   * @param keyStorePassword
   *   Password for the keystore.
   * @param trustStorePath
   *   Path to the truststore. If absent, the JVM's default trust store is used.
   * @param trustStorePassword
   *   Password for the truststore.
   * @param algorithm
   *   TLS algorithm (e.g. `"TLSv1.3"`). Defaults to jnats default if absent.
   * @param tlsFirst
   *   If true, initiate TLS immediately on connect rather than waiting for a
   *   server-side upgrade (`tls_required` servers).
   */
  case KeyStore(
    keyStorePath: Path,
    keyStorePassword: String,
    trustStorePath: Option[Path] = None,
    trustStorePassword: Option[String] = None,
    algorithm: Option[String] = None,
    tlsFirst: Boolean = false
  )

  /**
   * TLS using a pre-built [[javax.net.ssl.SSLContext]].
   *
   * Use when certificates are loaded at runtime (e.g. from a secrets manager,
   * HSM, or injected as in-memory PEM bytes) and cannot be expressed as files
   * on disk. For static file-based TLS, prefer [[KeyStore]].
   *
   * @param context
   *   A fully configured [[javax.net.ssl.SSLContext]].
   */
  case Custom(context: SSLContext)

  private[nats] def applyTo(builder: Options.Builder): Options.Builder =
    this match
      case Disabled                                   => builder
      case SystemDefault                              => builder.sslContext(SSLContext.getDefault)
      case KeyStore(ksp, kspw, tsp, tspw, alg, first) =>
        var b = builder
          .keystorePath(ksp.toString)
          .keystorePassword(kspw.toCharArray)
        tsp.foreach(p => b = b.truststorePath(p.toString))
        tspw.foreach(p => b = b.truststorePassword(p.toCharArray))
        alg.foreach(a => b = b.tlsAlgorithm(a))
        if first then b = b.tlsFirst()
        b
      case Custom(ctx) => builder.sslContext(ctx)

  /**
   * Returns the config type key for this variant, or [[None]] for variants that
   * are not reachable from text config ([[Custom]]).
   *
   * Exhaustive match — compile-time guard for [[NatsConfig.tlsConfig]]. See
   * [[NatsAuth.configTypeKey]] for the rationale.
   */
  private[config] def configTypeKey: Option[String] = this match
    case Disabled                   => Some(NatsTls.Keys.disabled)
    case SystemDefault              => Some(NatsTls.Keys.systemDefault)
    case KeyStore(_, _, _, _, _, _) => Some(NatsTls.Keys.keyStore)
    case Custom(_)                  => None

object NatsTls:
  /**
   * Config type key constants for all text-configurable [[NatsTls]] variants.
   * Referenced in [[NatsConfig.tlsConfig]] — single source of truth.
   */
  private[config] object Keys:
    val disabled      = "disabled"
    val systemDefault = "system-default"
    val keyStore      = "key-store"
