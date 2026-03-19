package zio.nats.service

import io.nats.client.{Connection => JConnection}
import io.nats.service.{
  Group => JGroup,
  ServiceEndpoint => JServiceEndpoint,
  ServiceMessage => JServiceMessage,
  ServiceMessageHandler
}
import zio._
import zio.nats.{Headers, NatsCodec, Subject}

import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
// ServiceEndpoint — typed descriptor
// ---------------------------------------------------------------------------

/**
 * A declarative, typed descriptor for a NATS service endpoint.
 *
 * Captures the endpoint's name, optional subject override, queue group policy,
 * optional group prefix, metadata, and — crucially — the input type, error
 * type, and output type with their [[NatsCodec]] / [[ServiceErrorMapper]]
 * instances. The descriptor is inert: it describes the shape of an endpoint but
 * contains no handler logic.
 *
 * To create a running endpoint, call [[implement]] or [[implementWithRequest]]
 * to bind a handler function:
 *
 * {{{
 * // Infallible endpoint (Err = Nothing)
 * val echo = ServiceEndpoint[String, String]("echo")
 * val bound = echo.implement(payload => ZIO.succeed(payload))
 *
 * // Endpoint with a domain error type
 * given ServiceErrorMapper[UserError] with { ... }
 * val lookup = ServiceEndpoint[UserQuery, UserResponse]("lookup").withError[UserError]
 * val bound2 = lookup.implement(q => UserRepo.find(q))
 * }}}
 *
 * @tparam In
 *   The request payload type (decoded from bytes via `NatsCodec[In]`).
 * @tparam Err
 *   The handler error type. Use `Nothing` for infallible handlers. Use
 *   `.withError[E]` on a 2-type-param endpoint to set a domain error type.
 * @tparam Out
 *   The response payload type (encoded to bytes via `NatsCodec[Out]`).
 */
final class ServiceEndpoint[In, Err, Out](
  /** Endpoint name — used as the NATS subject unless [[subject]] is set. */
  val name: String,
  /**
   * Optional subject override. If [[None]], the [[name]] is used as the NATS
   * subject. Wildcards are supported (e.g. `"events.>"`).
   */
  val subject: Option[String] = None,
  /** Queue group policy for load balancing (default: use `"q"`). */
  val queueGroup: QueueGroupPolicy = QueueGroupPolicy.Default,
  /** Optional group prefix to prepend to the endpoint subject. */
  val group: Option[ServiceGroup] = None,
  /** Optional key-value metadata attached to this endpoint. */
  val metadata: Map[String, String] = Map.empty
)(using val inCodec: NatsCodec[In], val outCodec: NatsCodec[Out], val errorMapper: ServiceErrorMapper[Err]):

  /**
   * Bind a handler function to this endpoint, producing a [[BoundEndpoint]].
   *
   * The framework:
   *   - Decodes incoming bytes as `In` using the captured `NatsCodec[In]`.
   *   - Passes the decoded value to `handler`.
   *   - Encodes the `Out` result using the captured `NatsCodec[Out]`.
   *   - Sends the encoded bytes as the NATS reply.
   *
   * If the handler fails with `Err`, the `ServiceErrorMapper[Err]` (captured on
   * this endpoint) converts it to a NATS service error response. If decoding or
   * encoding fails, a 500 error response is sent automatically.
   *
   * {{{
   * val echo = ServiceEndpoint[String, String]("echo")
   * val bound = echo.implement(name => ZIO.succeed(s"Hello, $name!"))
   * }}}
   */
  def implement(handler: In => IO[Err, Out]): BoundEndpoint =
    new BoundEndpointLive[In, Err, Out](
      name = name,
      endpoint = this,
      handler = req => handler(req.value)
    )

  /**
   * Bind a handler that also receives request metadata (subject, headers).
   *
   * Use this when you need access to NATS headers or the actual subject the
   * request arrived on (e.g. for wildcard subjects):
   *
   * {{{
   * val ep = ServiceEndpoint[String, String]("events.>")
   *
   * val bound = ep.implementWithRequest { req =>
   *   val topic   = req.subject.value  // e.g. "events.user.created"
   *   val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
   *   ZIO.succeed(s"Processed ${req.value} on $topic")
   * }
   * }}}
   */
  def implementWithRequest(handler: ServiceRequest[In] => IO[Err, Out]): BoundEndpoint =
    new BoundEndpointLive[In, Err, Out](
      name = name,
      endpoint = this,
      handler = handler
    )

  /**
   * Return a copy of this endpoint descriptor with the error type refined to
   * `E`. Requires a `ServiceErrorMapper[E]` in implicit scope.
   *
   * {{{
   * given ServiceErrorMapper[UserError] with { ... }
   * val ep = ServiceEndpoint[UserQuery, UserResponse]("lookup").withError[UserError]
   * val bound = ep.implement(q => UserRepo.find(q))
   * }}}
   */
  def withError[E: ServiceErrorMapper]: ServiceEndpoint[In, E, Out] =
    new ServiceEndpoint[In, E, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      summon[ServiceErrorMapper[E]]
    )

// ---------------------------------------------------------------------------
// ServiceEndpoint companion — 2-type-param factory for infallible endpoints
// ---------------------------------------------------------------------------

object ServiceEndpoint:
  /**
   * Construct an infallible endpoint descriptor (`Err = Nothing`).
   *
   * This is the primary factory for endpoints whose handlers never fail. The
   * resulting `ServiceEndpoint[In, Nothing, Out]` does not require an explicit
   * `[Nothing]` annotation on `implement`:
   *
   * {{{
   * val echo = ServiceEndpoint[String, String]("echo")
   * val bound = echo.implement(ZIO.succeed(_)) // no [Nothing] needed
   * }}}
   */
  def apply[In, Out](
    name: String,
    subject: Option[String] = None,
    queueGroup: QueueGroupPolicy = QueueGroupPolicy.Default,
    group: Option[ServiceGroup] = None,
    metadata: Map[String, String] = Map.empty
  )(using NatsCodec[In], NatsCodec[Out]): ServiceEndpoint[In, Nothing, Out] =
    new ServiceEndpoint[In, Nothing, Out](name, subject, queueGroup, group, metadata)

// ---------------------------------------------------------------------------
// BoundEndpoint — type-erased, ready-to-register unit
// ---------------------------------------------------------------------------

/**
 * An endpoint with its handler bound, ready to be registered on a
 * [[NatsService]].
 *
 * Created by calling [[ServiceEndpoint.implement]] or
 * [[ServiceEndpoint.implementWithRequest]]. The input/output types are erased
 * at this level — the codec and handler are captured inside. This allows
 * multiple `BoundEndpoint`s with different type signatures to be passed
 * together to [[zio.nats.Nats.service]].
 *
 * Users never construct this directly.
 */
trait BoundEndpoint:
  /** The endpoint name, used for introspection and logging. */
  def name: String

  /** Build the jnats ServiceEndpoint for registration. Internal use only. */
  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint

// ---------------------------------------------------------------------------
// BoundEndpointLive — private implementation
// ---------------------------------------------------------------------------

private[nats] class BoundEndpointLive[In, Err, Out](
  val name: String,
  endpoint: ServiceEndpoint[In, Err, Out],
  handler: ServiceRequest[In] => IO[Err, Out]
) extends BoundEndpoint:

  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint =
    val inCodec     = endpoint.inCodec
    val outCodec    = endpoint.outCodec
    val errorMapper = endpoint.errorMapper

    val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.fork {
          val program = for {
            // Decode the incoming bytes as In
            in <- ZIO
                    .fromEither(inCodec.decode(Chunk.fromArray(Option(jMsg.getData).getOrElse(Array.emptyByteArray))))
                    .mapError(e => ("Decoding failed: " + e.message, 500))

            // Build the typed request wrapper
            req = ServiceRequest(
                    value = in,
                    subject = Subject(jMsg.getSubject),
                    headers = headersFromJava(jMsg)
                  )

            // Run the user handler, converting Err → (String, Int)
            out <- handler(req).mapError(e => errorMapper.toErrorResponse(e))

            // Encode the response and send it
            enc <- ZIO
                     .attempt(outCodec.encode(out))
                     .mapError(e => ("Encoding failed: " + e.getMessage, 500))
            _ <- ZIO
                   .attempt(jMsg.respond(conn, enc.toArray))
                   .mapError(e => ("Response failed: " + e.getMessage, 500))
          } yield ()

          // On any error, send a NATS service error response
          program.catchAll { case (msg, code) =>
            ZIO.attempt(jMsg.respondStandardError(conn, msg, code)).ignoreLogged
          }
        }
      }
    }

    val b = JServiceEndpoint
      .builder()
      .endpointName(endpoint.name)
      .handler(jHandler)

    endpoint.subject.foreach(b.endpointSubject)

    endpoint.queueGroup match
      case QueueGroupPolicy.Default    => () // jnats uses "q" by default
      case QueueGroupPolicy.Disabled   => b.endpointQueueGroup(null)
      case QueueGroupPolicy.Custom(qg) => b.endpointQueueGroup(qg)

    endpoint.group.foreach(g => b.group(buildGroup(g)))

    if (endpoint.metadata.nonEmpty)
      b.endpointMetadata(endpoint.metadata.asJava)

    b.build()

  /**
   * Build a jnats [[JGroup]] from a [[ServiceGroup]], walking the parent chain
   * from root to leaf so that `appendGroup` produces the correct subject
   * prefix.
   *
   * For `ServiceGroup("v2", parent = Some(ServiceGroup("api")))` this produces
   * the equivalent of `new Group("api").appendGroup(new Group("v2"))`, which
   * resolves endpoint subjects as `"api.v2.<name>"`.
   */
  private def buildGroup(g: ServiceGroup): JGroup =
    g.parent match
      case None =>
        new JGroup(g.name)
      case Some(parent) =>
        val jParent = buildGroup(parent)
        val jSelf   = new JGroup(g.name)
        jParent.appendGroup(jSelf)
        jParent

  /**
   * Convert a jnats ServiceMessage's headers to [[Headers]].
   *
   * Uses the same pattern as [[zio.nats.NatsMessage.fromJava]].
   */
  private def headersFromJava(jMsg: JServiceMessage): Headers = {
    import scala.jdk.CollectionConverters._
    val jh = jMsg.getHeaders
    if (jh == null) Headers.empty
    else {
      val m = jh
        .keySet()
        .asScala
        .map(key => key -> Chunk.fromIterable(jh.get(key).asScala))
        .toMap
      Headers(m)
    }
  }
