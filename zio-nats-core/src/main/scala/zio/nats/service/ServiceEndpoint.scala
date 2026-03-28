package zio.nats.service

import io.nats.client.{Connection => JConnection}
import io.nats.service.{
  Group => JGroup,
  ServiceEndpoint => JServiceEndpoint,
  ServiceMessage => JServiceMessage,
  ServiceMessageHandler
}
import zio._
import zio.nats.{TypedErrorCodec, ErrorCodecPart, Headers, NatsCodec, NatsMessage, Subject}
import scala.reflect.ClassTag

import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
// NamedEndpoint — step 1 of the builder (no type parameters yet)
// ---------------------------------------------------------------------------

/**
 * The first step of the [[ServiceEndpoint]] builder.
 *
 * Holds the endpoint name and all non-type configuration. Call [[in]] to fix
 * the request payload type and advance to [[EndpointIn]]:
 *
 * {{{
 * ServiceEndpoint("stock-check")
 *   .in[StockRequest]
 *   .out[StockReply]
 *   .failsWith[ValidationError]
 *   .failsWith[InventoryError]
 *   .handle { req => ZIO.succeed(StockReply(42, 3)) }
 * }}}
 *
 * Configuration methods (`inGroup`, `inSubject`, `withQueueGroup`,
 * `withMetadata`) can be called in any order before [[in]].
 */
final class NamedEndpoint(
  private[service] val name: String,
  private[service] val subject: Option[String] = None,
  private[service] val group: Option[ServiceGroup] = None,
  private[service] val queueGroup: QueueGroupPolicy = QueueGroupPolicy.Default,
  private[service] val metadata: Map[String, String] = Map.empty
) {

  /**
   * Fix the request payload type and advance to [[EndpointIn]].
   *
   * Requires a [[zio.nats.NatsCodec]] for `In` in implicit scope.
   */
  def in[In: NatsCodec]: EndpointIn[In] =
    new EndpointIn[In](name, subject, group, queueGroup, metadata)

  /** Prepend a group subject prefix to this endpoint. */
  def inGroup(g: ServiceGroup): NamedEndpoint =
    new NamedEndpoint(name, subject, Some(g), queueGroup, metadata)

  /** Prepend a named group subject prefix to this endpoint. */
  def inGroup(groupName: String): NamedEndpoint =
    new NamedEndpoint(name, subject, Some(ServiceGroup(groupName)), queueGroup, metadata)

  /**
   * Override the NATS subject this endpoint listens on.
   *
   * If not set, the endpoint name is used as the subject. Wildcards are
   * supported (e.g. `"events.>"`).
   */
  def inSubject(s: String): NamedEndpoint =
    new NamedEndpoint(name, Some(s), group, queueGroup, metadata)

  /** Set the queue group policy for load balancing. */
  def withQueueGroup(policy: QueueGroupPolicy): NamedEndpoint =
    new NamedEndpoint(name, subject, group, policy, metadata)

  /** Add a key-value metadata entry exposed via service discovery. */
  def withMetadata(key: String, value: String): NamedEndpoint =
    new NamedEndpoint(name, subject, group, queueGroup, metadata + (key -> value))
}

// ---------------------------------------------------------------------------
// EndpointIn[In] — step 2 of the builder (In fixed, waiting for Out)
// ---------------------------------------------------------------------------

/**
 * The second step of the [[ServiceEndpoint]] builder.
 *
 * The request payload type `In` is fixed. Call [[out]] to fix the reply type
 * and produce a fully-typed [[ServiceEndpoint]]:
 *
 * {{{
 * ServiceEndpoint("echo")
 *   .in[String]    // EndpointIn[String]
 *   .out[String]   // ServiceEndpoint[String, Nothing, String]
 * }}}
 */
final class EndpointIn[In](
  private[service] val name: String,
  private[service] val subject: Option[String],
  private[service] val group: Option[ServiceGroup],
  private[service] val queueGroup: QueueGroupPolicy,
  private[service] val metadata: Map[String, String]
)(using val inCodec: NatsCodec[In]) {

  /**
   * Fix the reply payload type and produce a fully-typed [[ServiceEndpoint]].
   *
   * Requires a [[zio.nats.NatsCodec]] for `Out` in implicit scope. The error
   * type defaults to `Nothing` (infallible); call [[ServiceEndpoint.failsWith]]
   * one or more times to introduce domain error types.
   */
  def out[Out: NatsCodec]: ServiceEndpoint[In, Nothing, Out] =
    new ServiceEndpoint[In, Nothing, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      summon[NatsCodec[Out]],
      summon[ServiceErrorMapper[Nothing]],
      summon[TypedErrorCodec[Nothing]]
    )
}

// ---------------------------------------------------------------------------
// ServiceEndpoint — typed descriptor (step 3 / final)
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
 * Construct via the [[ServiceEndpoint]] companion and the builder chain:
 *
 * {{{
 * // Infallible endpoint
 * val echo = ServiceEndpoint("echo")
 *   .in[String]
 *   .out[String]
 *
 * // Fallible endpoint
 * val lookup = ServiceEndpoint("lookup")
 *   .in[UserQuery]
 *   .out[UserResponse]
 *   .failsWith[NotFound]
 *   .failsWith[Forbidden]
 * }}}
 *
 * To create a running endpoint, call [[handle]] or [[handleWith]] to bind a
 * handler function.
 *
 * @tparam In
 *   The request payload type (decoded from bytes via `NatsCodec[In]`).
 * @tparam Err
 *   The handler error type. `Nothing` for infallible handlers.
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
)(using
  val inCodec: NatsCodec[In],
  val outCodec: NatsCodec[Out],
  val errorMapper: ServiceErrorMapper[Err],
  val errCodec: TypedErrorCodec[Err]
) {

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
   * val echo = ServiceEndpoint("echo").in[String].out[String]
   * val bound = echo.handle(name => ZIO.succeed(s"Hello, $name!"))
   * }}}
   */
  def handle(handler: In => IO[Err, Out]): BoundEndpoint =
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
   * val ep = ServiceEndpoint("events.>").in[String].out[String]
   *
   * val bound = ep.handleWith { req =>
   *   val topic   = req.subject.value  // e.g. "events.user.created"
   *   val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
   *   ZIO.succeed(s"Processed ${req.value} on $topic")
   * }
   * }}}
   */
  def handleWith(handler: ServiceRequest[In] => IO[Err, Out]): BoundEndpoint =
    new BoundEndpointLive[In, Err, Out](
      name = name,
      endpoint = this,
      handler = handler
    )

  /**
   * The NATS subject this endpoint listens on and that callers should send
   * requests to.
   *
   * Derived from the optional [[subject]] override, the endpoint [[name]], and
   * any [[group]] prefix chain. For example, an endpoint named `"check"` inside
   * `ServiceGroup("catalog")` has
   * `effectiveSubject == Subject("catalog.check")`.
   *
   * Use this with [[zio.nats.Nats.requestService]] or directly with
   * [[zio.nats.Nats.request]] for infallible endpoints.
   */
  def effectiveSubject: Subject = {
    val base = subject.getOrElse(name)
    group.fold(Subject(base))(g => Subject(groupPrefix(g) + "." + base))
  }

  private def groupPrefix(g: ServiceGroup): String =
    g.parent.fold(g.name)(p => groupPrefix(p) + "." + g.name)

  /**
   * Return a copy of this endpoint descriptor with `E` appended to the error
   * channel.
   *
   * Chaining accumulates union members, so repeated calls widen the endpoint's
   * error type from `Err` to `Err | E`.
   *
   * A `ServiceErrorMapper[E]` is resolved automatically via the universal
   * fallback (uses `e.toString` with code 500). Provide a specific
   * [[ServiceErrorMapper]] instance to customise the `Nats-Service-Error`
   * header value or HTTP-style status code.
   *
   * Repeated calls for the same type are accepted. The newest member is tried
   * first during server-side encoding; client-side decoding continues to route
   * by the `Nats-Service-Error-Type` header.
   *
   * {{{
   * val ep = ServiceEndpoint("lookup")
   *   .in[UserQuery]
   *   .out[UserResponse]
   *   .failsWith[NotFound]
   *   .failsWith[Forbidden]
   * }}}
   */
  def failsWith[E: NatsCodec: ClassTag](using mapperE: ServiceErrorMapper[E]): ServiceEndpoint[In, Err | E, Out] =
    val part                                       = summon[ErrorCodecPart[E]]
    val widenedMapper: ServiceErrorMapper[Err | E] = err =>
      if part.matches(err) then mapperE.toErrorResponse(err.asInstanceOf[E])
      else errorMapper.toErrorResponse(err.asInstanceOf[Err])
    val widenedCodec: TypedErrorCodec[Err | E] = TypedErrorCodec.append(errCodec, part)
    new ServiceEndpoint[In, Err | E, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      widenedMapper,
      widenedCodec
    )
}

// ---------------------------------------------------------------------------
// ServiceEndpoint companion — builder entry point
// ---------------------------------------------------------------------------

object ServiceEndpoint {

  /**
   * Start building a service endpoint with the given name.
   *
   * Returns a [[NamedEndpoint]] builder. Chain [[NamedEndpoint.in]],
   * [[EndpointIn.out]], and optionally [[ServiceEndpoint.failsWith]] to
   * assemble the full typed descriptor, then [[ServiceEndpoint.handle]] to bind
   * a handler:
   *
   * {{{
   * // Infallible
   * val echo = ServiceEndpoint("echo")
   *   .in[String]
   *   .out[String]
   *   .handle(ZIO.succeed(_))
   *
   * // Fallible
   * val stock = ServiceEndpoint("stock-check")
   *   .in[StockRequest]
   *   .out[StockReply]
   *   .failsWith[StockError]
   *   .handle { req => ZIO.succeed(StockReply(42, 3)) }
   * }}}
   */
  def apply(name: String): NamedEndpoint = new NamedEndpoint(name)
}

// ---------------------------------------------------------------------------
// BoundEndpoint — type-erased, ready-to-register unit
// ---------------------------------------------------------------------------

/**
 * An endpoint with its handler bound, ready to be registered on a
 * [[NatsService]].
 *
 * Created by calling [[ServiceEndpoint.handle]] or
 * [[ServiceEndpoint.handleWith]]. The input/output types are erased at this
 * level — the codec and handler are captured inside. This allows multiple
 * `BoundEndpoint`s with different type signatures to be passed together to
 * [[zio.nats.Nats.service]].
 *
 * Users never construct this directly.
 */
trait BoundEndpoint {

  /** The endpoint name, used for introspection and logging. */
  def name: String

  /** Build the jnats ServiceEndpoint for registration. Internal use only. */
  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint
}

// ---------------------------------------------------------------------------
// BoundEndpointLive — private implementation
// ---------------------------------------------------------------------------

/** jnats-backed implementation of [[BoundEndpoint]]. */
private[nats] class BoundEndpointLive[In, Err, Out](
  val name: String,
  endpoint: ServiceEndpoint[In, Err, Out],
  handler: ServiceRequest[In] => IO[Err, Out]
) extends BoundEndpoint {

  /**
   * Build the jnats [[ServiceMessageHandler]] that decodes requests, runs the
   * user handler, and encodes replies — isolated from the builder wiring in
   * [[buildJava]].
   */
  private def makeHandler(conn: JConnection, runtime: Runtime[Any]): ServiceMessageHandler = {
    val inCodec     = endpoint.inCodec
    val outCodec    = endpoint.outCodec
    val errorMapper = endpoint.errorMapper
    val errCodec    = endpoint.errCodec

    { (jMsg: JServiceMessage) =>
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.fork {
          // Error channel: Left = typed domain error, Right = infrastructure (message, code)
          val program: IO[Either[Err, (String, Int)], Unit] = for {
            // Decode the incoming bytes as In
            in <- ZIO
                    .fromEither(inCodec.decode(Chunk.fromArray(Option(jMsg.getData).getOrElse(Array.emptyByteArray))))
                    .mapError(e => Right(("Decoding failed: " + e.message, 500)))

            // Build the typed request wrapper
            req = ServiceRequest(
                    value = in,
                    subject = Subject(jMsg.getSubject),
                    headers = Headers.fromJava(jMsg.getHeaders)
                  )

            // Run the user handler — preserve the typed Err in the Left channel
            out <- handler(req).mapError(e => Left(e))

            // Encode the response and send it
            enc <- ZIO
                     .attempt(outCodec.encode(out))
                     .mapError(e => Right(("Encoding failed: " + e.getMessage, 500)))
            _ <- ZIO
                   .attempt(jMsg.respond(conn, enc.toArray))
                   .mapError(e => Right(("Response failed: " + e.getMessage, 500)))
          } yield ()

          program.catchAll {
            case Left(err) =>
              // Domain error: encode typed body + set NATS Micro error headers
              val (encoded, typeTag) = errCodec.encode(err)
              val (errMsg, errCode)  = errorMapper.toErrorResponse(err)
              ZIO.attempt {
                val replyMsg = NatsMessage.toJava(
                  subject = jMsg.getReplyTo,
                  data = encoded,
                  headers = Headers(
                    "Nats-Service-Error"      -> errMsg,
                    "Nats-Service-Error-Code" -> errCode.toString,
                    "Nats-Service-Error-Type" -> typeTag
                  )
                )
                conn.publish(replyMsg)
              }.ignoreLogged

            case Right((msg, code)) =>
              // Infrastructure error: empty body via respondStandardError (unchanged)
              ZIO.attempt(jMsg.respondStandardError(conn, msg, code)).ignoreLogged
          }
        }
      }
    }
  }

  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint = {
    val b = JServiceEndpoint
      .builder()
      .endpointName(endpoint.name)
      .handler(makeHandler(conn, runtime))

    endpoint.subject.foreach(b.endpointSubject)

    endpoint.queueGroup match {
      case QueueGroupPolicy.Default    => () // jnats uses "q" by default
      case QueueGroupPolicy.Disabled   => b.endpointQueueGroup(null)
      case QueueGroupPolicy.Custom(qg) => b.endpointQueueGroup(qg)
    }

    endpoint.group.foreach(g => b.group(buildGroup(g)))

    if (endpoint.metadata.nonEmpty)
      b.endpointMetadata(endpoint.metadata.asJava)

    b.build()
  }

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
    g.parent match {
      case None =>
        new JGroup(g.name)
      case Some(parent) =>
        val jParent = buildGroup(parent)
        val jSelf   = new JGroup(g.name)
        jParent.appendGroup(jSelf)
        jParent
    }

}
