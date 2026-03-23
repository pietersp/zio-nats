package zio.nats.service

import zio.Duration

/**
 * Configuration for a NATS microservice.
 *
 * @param name
 *   Service name (alphanumeric, hyphens, underscores only).
 * @param version
 *   Semantic version string (e.g. `"1.0.0"`).
 * @param description
 *   Optional human-readable description exposed via discovery.
 * @param metadata
 *   Optional key-value metadata exposed via discovery.
 * @param drainTimeout
 *   Timeout for draining subscriptions on stop. If [[None]], the jnats default
 *   is used.
 */
final case class ServiceConfig(
  name: String,
  version: String,
  description: Option[String] = None,
  metadata: Map[String, String] = Map.empty,
  drainTimeout: Option[Duration] = None
)

/**
 * A subject prefix group for organizing service endpoints.
 *
 * Groups are prepended to endpoint subjects and can be nested:
 *
 * {{{
 * val api = ServiceGroup("api")
 * val v2  = ServiceGroup("v2", parent = Some(api))
 * // endpoint subject "users" becomes "api.v2.users"
 * }}}
 */
final case class ServiceGroup(
  name: String,
  parent: Option[ServiceGroup] = None
)

/**
 * Controls queue group behaviour for a service endpoint.
 *
 * The NATS service framework uses queue groups for transparent load balancing:
 * when multiple instances of the same service run, each request is delivered to
 * exactly one instance.
 *
 *   - [[QueueGroupPolicy.Default]] — use the standard service queue group
 *     `"q"`.
 *   - [[QueueGroupPolicy.Disabled]] — disable queue grouping so every instance
 *     receives every request.
 *   - [[QueueGroupPolicy.Custom]] — use a caller-supplied queue group name.
 */
enum QueueGroupPolicy:
  case Default
  case Disabled
  case Custom(name: String)

/**
 * Typeclass for converting handler error types to NATS service error responses.
 *
 * NATS service errors are a `(message, code)` pair surfaced to callers via the
 * `Nats-Service-Error` and `Nats-Service-Error-Code` headers. These headers are
 * set for NATS Micro protocol compatibility (monitoring, discovery,
 * cross-language clients).
 *
 * A universal fallback instance is provided — `e.toString` with code 500 — so
 * no explicit instance is required for custom domain error types. Override the
 * default for custom header values or HTTP-style status codes:
 *
 * {{{
 * enum UserError:
 *   case NotFound(id: Int)
 *   case InvalidInput(msg: String)
 *
 * given ServiceErrorMapper[UserError] with
 *   def toErrorResponse(e: UserError): (String, Int) = e match
 *     case UserError.NotFound(id)      => (s"User $id not found", 404)
 *     case UserError.InvalidInput(msg) => (msg, 400)
 * }}}
 */
trait ServiceErrorMapper[E]:
  /**
   * Convert an error to a `(message, errorCode)` pair for the NATS protocol.
   */
  def toErrorResponse(e: E): (String, Int)

object ServiceErrorMapper extends LowPriorityServiceErrorMappers:

  /**
   * Default mapper for [[zio.nats.NatsError]]: sends the error message with
   * code 500.
   */
  given ServiceErrorMapper[zio.nats.NatsError] with
    def toErrorResponse(e: zio.nats.NatsError): (String, Int) = (e.message, 500)

  /** Mapper for plain [[String]] errors: sends the string with code 500. */
  given ServiceErrorMapper[String] with
    def toErrorResponse(e: String): (String, Int) = (e, 500)

  /**
   * Mapper for `Nothing`: trivially satisfied because a handler with error type
   * `Nothing` can never actually fail.
   */
  given ServiceErrorMapper[Nothing] with
    def toErrorResponse(e: Nothing): (String, Int) = throw new AssertionError("unreachable")

/**
 * Lower-priority given instances for [[ServiceErrorMapper]].
 *
 * The universal fallback lives here so that specific instances in
 * [[ServiceErrorMapper]] companion always take precedence.
 */
private[nats] trait LowPriorityServiceErrorMappers:

  /**
   * Universal fallback mapper: uses `e.toString` as the error message with code
   * 500. Enables `withError[E]` for any `E` without requiring an explicit
   * `ServiceErrorMapper[E]` instance.
   */
  given [E]: ServiceErrorMapper[E] with
    def toErrorResponse(e: E): (String, Int) = (e.toString, 500)
