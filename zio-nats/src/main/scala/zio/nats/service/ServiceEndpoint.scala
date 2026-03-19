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
 * optional group prefix, metadata, and — crucially — the input and output types
 * with their [[NatsCodec]] instances. The descriptor is inert: it describes the
 * shape of an endpoint but contains no handler logic.
 *
 * To create a running endpoint, call [[implement]] or [[implementWithRequest]]
 * to bind a handler function:
 *
 * {{{
 * // 1. Declare the endpoint shape
 * val lookup = ServiceEndpoint[UserQuery, UserResponse]("lookup")
 *
 * // 2. Bind a handler — the framework handles decode/encode
 * val bound = lookup.implement { query =>
 *   ZIO.succeed(UserResponse(query.id, "Alice", "alice@example.com"))
 * }
 *
 * // 3. Register on a service
 * nats.service(ServiceConfig("users", "1.0.0"), bound)
 * }}}
 *
 * @tparam In
 *   The request payload type (decoded from bytes via `NatsCodec[In]`).
 * @tparam Out
 *   The response payload type (encoded to bytes via `NatsCodec[Out]`).
 */
final case class ServiceEndpoint[In, Out](
  /** Endpoint name — used as the NATS subject unless [[subject]] is set. */
  name: String,
  /**
   * Optional subject override. If [[None]], the [[name]] is used as the NATS
   * subject. Wildcards are supported (e.g. `"events.>"`).
   */
  subject: Option[String] = None,
  /** Queue group policy for load balancing (default: use `"q"`). */
  queueGroup: QueueGroupPolicy = QueueGroupPolicy.Default,
  /** Optional group prefix to prepend to the endpoint subject. */
  group: Option[ServiceGroup] = None,
  /** Optional key-value metadata attached to this endpoint. */
  metadata: Map[String, String] = Map.empty
)(using val inCodec: NatsCodec[In], val outCodec: NatsCodec[Out]):

  /**
   * Bind a handler function to this endpoint, producing a [[BoundEndpoint]].
   *
   * The framework:
   *   - Decodes incoming bytes as `In` using the captured `NatsCodec[In]`.
   *   - Passes the decoded value to `handler`.
   *   - Encodes the `Out` result using the captured `NatsCodec[Out]`.
   *   - Sends the encoded bytes as the NATS reply.
   *
   * If the handler fails with `Err`, the `ServiceErrorMapper[Err]` converts it
   * to a NATS service error response. If decoding or encoding fails, a 500
   * error response is sent automatically.
   *
   * {{{
   * val echo = ServiceEndpoint[String, String]("echo")
   * val bound = echo.implement(name => ZIO.succeed(s"Hello, $name!"))
   * }}}
   */
  def implement[Err: ServiceErrorMapper](
    handler: In => IO[Err, Out]
  ): BoundEndpoint =
    new BoundEndpointLive[In, Err, Out](
      name = name,
      endpoint = this,
      handler = req => handler(req.value),
      errorMapper = summon[ServiceErrorMapper[Err]]
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
  def implementWithRequest[Err: ServiceErrorMapper](
    handler: ServiceRequest[In] => IO[Err, Out]
  ): BoundEndpoint =
    new BoundEndpointLive[In, Err, Out](
      name = name,
      endpoint = this,
      handler = handler,
      errorMapper = summon[ServiceErrorMapper[Err]]
    )

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
  endpoint: ServiceEndpoint[In, Out],
  handler: ServiceRequest[In] => IO[Err, Out],
  errorMapper: ServiceErrorMapper[Err]
) extends BoundEndpoint:

  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint =
    val inCodec  = endpoint.inCodec
    val outCodec = endpoint.outCodec

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
