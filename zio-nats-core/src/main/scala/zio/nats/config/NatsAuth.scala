package zio.nats.config

import io.nats.client.{AuthHandler, Options}
import java.nio.file.Path

/**
 * Authentication method for a NATS connection.
 *
 * Exactly one authentication method is active at a time. Invalid combinations
 * (e.g. token and user/password simultaneously) are impossible to construct,
 * unlike the previous flat-field approach where multiple auth fields could be
 * set with undefined precedence.
 *
 * `NoAuth`, `Token`, `UserPassword`, and `CredentialFile` are fully
 * text-configurable. `Custom` accepts a pre-built
 * [[io.nats.client.AuthHandler]] for dynamic credential rotation or credentials
 * loaded at runtime.
 *
 * {{{
 *   // Anonymous
 *   NatsConfig()
 *
 *   // Token
 *   NatsConfig(auth = NatsAuth.Token("s3cr3t"))
 *
 *   // Username + password
 *   NatsConfig(auth = NatsAuth.UserPassword("alice", "p4ssw0rd"))
 *
 *   // NKey/JWT from a .creds file
 *   NatsConfig(auth = NatsAuth.CredentialFile(Paths.get("/run/secrets/nats.creds")))
 *
 *   // Runtime AuthHandler (e.g. dynamic credential rotation)
 *   NatsConfig(auth = NatsAuth.Custom(myAuthHandler))
 * }}}
 */
enum NatsAuth:
  /** No authentication — anonymous connection. */
  case NoAuth

  /**
   * Static authentication token.
   *
   * @param value
   *   The authentication token.
   */
  case Token(value: String)

  /**
   * Username and password authentication.
   *
   * @param username
   *   The username.
   * @param password
   *   The password.
   */
  case UserPassword(username: String, password: String)

  /**
   * NKey/JWT authentication from a `.creds` file.
   *
   * @param path
   *   Path to the `.creds` file on the filesystem.
   */
  case CredentialFile(path: Path)

  /**
   * Authentication via a programmatic [[io.nats.client.AuthHandler]].
   *
   * Use for dynamic credential rotation or credentials that are not available
   * as static text (e.g. NKey signing with a key loaded from a secrets manager
   * at runtime). For static credentials, prefer [[Token]], [[UserPassword]], or
   * [[CredentialFile]].
   *
   * @param handler
   *   A fully configured [[io.nats.client.AuthHandler]].
   */
  case Custom(handler: AuthHandler)

  private[nats] def applyTo(builder: Options.Builder): Options.Builder =
    this match
      case NoAuth             => builder
      case Token(v)           => builder.token(v.toCharArray)
      case UserPassword(u, p) => builder.userInfo(u.toCharArray, p.toCharArray)
      case CredentialFile(p)  => builder.credentialPath(p.toString)
      case Custom(h)          => builder.authHandler(h)

  /**
   * Returns the config type key for this variant, or [[None]] for variants that
   * are not reachable from text config ([[Custom]]).
   *
   * This exhaustive match is the compile-time guard for
   * [[NatsConfig.authConfig]]: adding a new [[NatsAuth]] case without updating
   * it produces a non-exhaustive match error, signalling that
   * [[NatsConfig.authConfig]] also needs updating.
   */
  private[config] def configTypeKey: Option[String] = this match
    case NoAuth             => Some(NatsAuth.Keys.noAuth)
    case Token(_)           => Some(NatsAuth.Keys.token)
    case UserPassword(_, _) => Some(NatsAuth.Keys.userPassword)
    case CredentialFile(_)  => Some(NatsAuth.Keys.credentialFile)
    case Custom(_)          => None

object NatsAuth:
  /**
   * Config type key constants for all text-configurable [[NatsAuth]] variants.
   * Referenced in [[NatsConfig.authConfig]] — kept here so they are co-located
   * with the type they describe and form a single source of truth.
   */
  private[config] object Keys:
    val noAuth         = "no-auth"
    val token          = "token"
    val userPassword   = "user-password"
    val credentialFile = "credential-file"
