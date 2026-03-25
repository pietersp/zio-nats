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
 *   .failsWith[StockError]
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
   * to introduce a domain error type.
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
 *   .failsWith[UserError]
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
   * Return a copy of this endpoint descriptor with the error type set to `E`.
   *
   * Requires `NatsCodec[E]` in implicit scope — `TypedErrorCodec[E]` is then
   * derived automatically from `NatsCodec[E]` and the `ClassTag[E]` that is
   * always available for concrete types.
   *
   * A `ServiceErrorMapper[E]` is resolved automatically via the universal
   * fallback (uses `e.toString` with code 500). Provide a specific
   * [[ServiceErrorMapper]] instance to customise the `Nats-Service-Error`
   * header value or HTTP-style status code.
   *
   * For union error types use the two- to five-parameter overloads:
   * [[failsWith[A,B]*]], [[failsWith[A,B,C]*]], [[failsWith[A,B,C,D]*]], or
   * [[failsWith[A,B,C,D,E]*]]. For more than five distinct error types, model
   * them as a sealed enum and use this single-type overload — a single
   * `NatsCodec` derived from the shared schema handles all cases, and a single
   * `ServiceErrorMapper` can pattern-match on the cases to emit fine-grained
   * status codes.
   *
   * {{{
   * val ep = ServiceEndpoint("lookup").in[UserQuery].out[UserResponse].failsWith[UserError]
   * }}}
   */
  def failsWith[E: TypedErrorCodec: ServiceErrorMapper]: ServiceEndpoint[In, E, Out] =
    new ServiceEndpoint[In, E, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      summon[ServiceErrorMapper[E]],
      summon[TypedErrorCodec[E]]
    )

  /**
   * Return a copy of this endpoint descriptor with a 2-member union error type
   * `A | B`.
   *
   * Both `A` and `B` must have `NatsCodec` instances in scope — the same
   * requirement as [[failsWith[E]*]] for a single type. `ClassTag` is always
   * available for concrete types and requires no explicit instance.
   *
   * The server sets a `Nats-Service-Error-Type` header to the FQDN of the
   * concrete runtime error class; the client dispatches decoding to the correct
   * member codec using that tag.
   *
   * `ServiceErrorMapper[A | B]` is composed from individual mapper instances
   * (both default to `e.toString` / code 500 if no explicit instance is
   * provided).
   *
   * {{{
   * val ep = ServiceEndpoint("order")
   *   .in[OrderRequest].out[OrderReply]
   *   .failsWith[ValidationError, PaymentError]
   * }}}
   */
  def failsWith[A: NatsCodec: ClassTag, B: NatsCodec: ClassTag](using
    ma: ServiceErrorMapper[A],
    mb: ServiceErrorMapper[B]
  ): ServiceEndpoint[In, A | B, Out] =
    val pa                                     = summon[ErrorCodecPart[A]]
    val pb                                     = summon[ErrorCodecPart[B]]
    val unionCodec: TypedErrorCodec[A | B]     = TypedErrorCodec.union2(pa, pb)
    val unionMapper: ServiceErrorMapper[A | B] = e =>
      if pa.matches(e) then ma.toErrorResponse(e.asInstanceOf[A])
      else mb.toErrorResponse(e.asInstanceOf[B])
    new ServiceEndpoint[In, A | B, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      unionMapper,
      unionCodec
    )

  /**
   * Return a copy of this endpoint descriptor with a 3-member union error type
   * `A | B | C`.
   *
   * Behaves identically to [[failsWith[A,B]*]] but for three error members.
   *
   * {{{
   * val ep = ServiceEndpoint("order")
   *   .in[OrderRequest].out[OrderReply]
   *   .failsWith[ValidationError, PaymentError, InventoryError]
   * }}}
   */
  def failsWith[A: NatsCodec: ClassTag, B: NatsCodec: ClassTag, C: NatsCodec: ClassTag](using
    ma: ServiceErrorMapper[A],
    mb: ServiceErrorMapper[B],
    mc: ServiceErrorMapper[C]
  ): ServiceEndpoint[In, A | B | C, Out] =
    val pa                                         = summon[ErrorCodecPart[A]]
    val pb                                         = summon[ErrorCodecPart[B]]
    val pc                                         = summon[ErrorCodecPart[C]]
    val unionCodec: TypedErrorCodec[A | B | C]     = TypedErrorCodec.union3(pa, pb, pc)
    val unionMapper: ServiceErrorMapper[A | B | C] = e =>
      if pa.matches(e) then ma.toErrorResponse(e.asInstanceOf[A])
      else if pb.matches(e) then mb.toErrorResponse(e.asInstanceOf[B])
      else mc.toErrorResponse(e.asInstanceOf[C])
    new ServiceEndpoint[In, A | B | C, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      unionMapper,
      unionCodec
    )

  /**
   * Return a copy of this endpoint descriptor with a 4-member union error type
   * `A | B | C | D`.
   *
   * Behaves identically to [[failsWith[A,B]*]] but for four error members.
   *
   * {{{
   * val ep = ServiceEndpoint("order")
   *   .in[OrderRequest].out[OrderReply]
   *   .failsWith[ValidationError, PaymentError, InventoryError, AuthError]
   * }}}
   */
  def failsWith[
    A: NatsCodec: ClassTag,
    B: NatsCodec: ClassTag,
    C: NatsCodec: ClassTag,
    D: NatsCodec: ClassTag
  ](using
    ma: ServiceErrorMapper[A],
    mb: ServiceErrorMapper[B],
    mc: ServiceErrorMapper[C],
    md: ServiceErrorMapper[D]
  ): ServiceEndpoint[In, A | B | C | D, Out] =
    val pa                                             = summon[ErrorCodecPart[A]]
    val pb                                             = summon[ErrorCodecPart[B]]
    val pc                                             = summon[ErrorCodecPart[C]]
    val pd                                             = summon[ErrorCodecPart[D]]
    val unionCodec: TypedErrorCodec[A | B | C | D]     = TypedErrorCodec.union4(pa, pb, pc, pd)
    val unionMapper: ServiceErrorMapper[A | B | C | D] = e =>
      if pa.matches(e) then ma.toErrorResponse(e.asInstanceOf[A])
      else if pb.matches(e) then mb.toErrorResponse(e.asInstanceOf[B])
      else if pc.matches(e) then mc.toErrorResponse(e.asInstanceOf[C])
      else md.toErrorResponse(e.asInstanceOf[D])
    new ServiceEndpoint[In, A | B | C | D, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      unionMapper,
      unionCodec
    )

  /**
   * Return a copy of this endpoint descriptor with a 5-member union error type
   * `A | B | C | D | E`.
   *
   * Behaves identically to [[failsWith[A,B]*]] but for five error members. This
   * is the maximum supported arity for union error types. For more than five
   * distinct error types, model them as a sealed enum and use the single-type
   * [[failsWith[E]*]] overload — a single `NatsCodec` covers all cases and a
   * `ServiceErrorMapper` can pattern-match for fine-grained codes.
   *
   * {{{
   * val ep = ServiceEndpoint("order")
   *   .in[OrderRequest].out[OrderReply]
   *   .failsWith[ValidationError, PaymentError, InventoryError, AuthError, RateLimited]
   * }}}
   */
  def failsWith[
    A: NatsCodec: ClassTag,
    B: NatsCodec: ClassTag,
    C: NatsCodec: ClassTag,
    D: NatsCodec: ClassTag,
    E: NatsCodec: ClassTag
  ](using
    ma: ServiceErrorMapper[A],
    mb: ServiceErrorMapper[B],
    mc: ServiceErrorMapper[C],
    md: ServiceErrorMapper[D],
    me: ServiceErrorMapper[E]
  ): ServiceEndpoint[In, A | B | C | D | E, Out] =
    val pa                                                 = summon[ErrorCodecPart[A]]
    val pb                                                 = summon[ErrorCodecPart[B]]
    val pc                                                 = summon[ErrorCodecPart[C]]
    val pd                                                 = summon[ErrorCodecPart[D]]
    val pe                                                 = summon[ErrorCodecPart[E]]
    val unionCodec: TypedErrorCodec[A | B | C | D | E]     = TypedErrorCodec.union5(pa, pb, pc, pd, pe)
    val unionMapper: ServiceErrorMapper[A | B | C | D | E] = e =>
      if pa.matches(e) then ma.toErrorResponse(e.asInstanceOf[A])
      else if pb.matches(e) then mb.toErrorResponse(e.asInstanceOf[B])
      else if pc.matches(e) then mc.toErrorResponse(e.asInstanceOf[C])
      else if pd.matches(e) then md.toErrorResponse(e.asInstanceOf[D])
      else me.toErrorResponse(e.asInstanceOf[E])
    new ServiceEndpoint[In, A | B | C | D | E, Out](name, subject, queueGroup, group, metadata)(using
      inCodec,
      outCodec,
      unionMapper,
      unionCodec
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

private[nats] class BoundEndpointLive[In, Err, Out](
  val name: String,
  endpoint: ServiceEndpoint[In, Err, Out],
  handler: ServiceRequest[In] => IO[Err, Out]
) extends BoundEndpoint {

  private[nats] def buildJava(conn: JConnection, runtime: Runtime[Any]): JServiceEndpoint = {
    val inCodec     = endpoint.inCodec
    val outCodec    = endpoint.outCodec
    val errorMapper = endpoint.errorMapper
    val errCodec    = endpoint.errCodec

    val jHandler: ServiceMessageHandler = { (jMsg: JServiceMessage) =>
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
                    headers = headersFromJava(jMsg)
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

    val b = JServiceEndpoint
      .builder()
      .endpointName(endpoint.name)
      .handler(jHandler)

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
}
