package zio.nats.config

import io.nats.client.Options
import java.nio.file.Path

/**
 * Authentication method for a NATS connection.
 *
 * Exactly one authentication method is active at a time. Invalid combinations
 * (e.g. token and user/password simultaneously) are impossible to construct,
 * unlike the previous flat-field approach where multiple auth fields could be
 * set with undefined precedence.
 *
 * For advanced use cases requiring a custom [[io.nats.client.AuthHandler]]
 * (e.g. dynamic credential rotation), use [[zio.nats.Nats.customized]] instead.
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

  private[nats] def applyTo(builder: Options.Builder): Options.Builder =
    this match
      case NoAuth             => builder
      case Token(v)           => builder.token(v.toCharArray)
      case UserPassword(u, p) => builder.userInfo(u.toCharArray, p.toCharArray)
      case CredentialFile(p)  => builder.credentialPath(p.toString)
