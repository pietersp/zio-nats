import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

/**
 * Demonstrates the NATS Service Framework (Micro Protocol).
 *
 * Run this example against a local NATS server:
 * {{{
 *   docker run -p 4222:4222 nats -js
 * }}}
 *
 * The example:
 *   1. Starts an "echo" service with a single typed endpoint.
 *   2. Calls it as a client using the normal request-reply API.
 *   3. Queries the service via the discovery API.
 *   4. Prints per-endpoint stats.
 *   5. Demonstrates union error types: a "lookup" endpoint that can fail with
 *      either `NotFound` or `ValidationError`, called via `requestService`.
 */
object ServiceApp extends ZIOAppDefault {

  // ---------------------------------------------------------------------------
  // Domain types for the union-error example (section 5)
  // ---------------------------------------------------------------------------

  case class LookupRequest(id: String)
  case class LookupReply(name: String, email: String)

  /** Returned when the requested resource does not exist. */
  case class NotFound(id: String)

  /**
   * Returned when the request itself is invalid (empty id, bad format, etc.).
   */
  case class ValidationError(field: String, reason: String)

  object LookupRequest   { given Schema[LookupRequest] = Schema.derived   }
  object LookupReply     { given Schema[LookupReply] = Schema.derived     }
  object NotFound        { given Schema[NotFound] = Schema.derived        }
  object ValidationError { given Schema[ValidationError] = Schema.derived }

  val codecs = NatsCodec.fromFormat(JsonFormat)
  import codecs.derived

  // ---------------------------------------------------------------------------
  // Endpoint descriptors
  // ---------------------------------------------------------------------------

  // Infallible echo endpoint — no domain error type.
  val echoEndpoint: ServiceEndpoint[String, Nothing, String] = ServiceEndpoint("echo").in[String].out[String]

  // Fallible lookup endpoint — two distinct error types form a union.
  val lookupEndpoint: ServiceEndpoint[LookupRequest, NotFound | ValidationError, LookupReply] = ServiceEndpoint(
    "lookup"
  )
    .in[LookupRequest]
    .out[LookupReply]
    .failsWith[NotFound, ValidationError]

  // Simulated in-memory user store.
  private val users = Map(
    "u1" -> LookupReply("Alice", "alice@example.com"),
    "u2" -> LookupReply("Bob", "bob@example.com")
  )

  val run: ZIO[Any, Exception, Unit] =
    ZIO
      .serviceWithZIO[Nats] { nats =>
        ZIO.scoped {
          for {
            // 1. Start the services.
            svc <- nats.service(
                     ServiceConfig(
                       name = "echo-service",
                       version = "1.0.0",
                       description = Some("Echoes every request back to the caller")
                     ),
                     echoEndpoint.handle(payload => ZIO.succeed(payload))
                   )

            _ <- Console.printLine(s"Service '${svc.name}' started [id=${svc.id}]")

            _ <- nats.service(
                   ServiceConfig(name = "lookup-service", version = "1.0.0"),
                   lookupEndpoint.handle { req =>
                     if (req.id.isBlank) ZIO.fail(ValidationError("id", "must not be blank"))
                     else
                       ZIO
                         .fromOption(users.get(req.id))
                         .orElseFail(NotFound(req.id))
                   }
                 )

            _ <- Console.printLine("Service 'lookup-service' started")
            _ <- ZIO.sleep(200.millis)

            // 2. Call the echo endpoint.
            reply1 <- nats.request[String, String](Subject("echo"), "hello world", 5.seconds)
            _      <- Console.printLine(s"\nEcho reply: ${reply1.value}")

            reply2 <- nats.request[String, String](Subject("echo"), "ZIO + NATS", 5.seconds)
            _      <- Console.printLine(s"Echo reply: ${reply2.value}")

            // 3. Query via the discovery API.
            discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
            pings     <- discovery.ping("echo-service")
            _         <- Console.printLine(s"\nDiscovered ${pings.size} echo-service instance(s):")
            _         <- ZIO.foreachDiscard(pings) { p =>
                   Console.printLine(s"  ${p.name} v${p.version} [${p.id}]")
                 }

            infos <- discovery.info("echo-service")
            _     <-
              ZIO.foreachDiscard(infos) { i =>
                Console.printLine(s"  Description: ${i.description.getOrElse("none")}") *>
                  ZIO.foreachDiscard(i.endpoints) { ep =>
                    Console.printLine(s"    endpoint '${ep.name}' -> subject '${ep.subject}' queue='${ep.queueGroup}'")
                  }
              }

            // 4. Print endpoint stats.
            _ <- ZIO.sleep(100.millis)
            s <- svc.stats
            _ <- Console.printLine(s"\nEndpoint statistics:")
            _ <- ZIO.foreachDiscard(s.endpoints) { ep =>
                   Console.printLine(
                     s"  ${ep.name}: ${ep.numRequests} requests, " +
                       s"${ep.numErrors} errors, " +
                       s"avg ${ep.averageProcessingTimeNanos / 1000}µs"
                   )
                 }

            // 5. Union error types: requestService returns IO[NatsError | NotFound | ValidationError, LookupReply].
            //    Domain errors surface directly in the ZIO error channel — no header inspection needed.
            _ <- Console.printLine("\n--- Union error type demo ---")

            r1 <- nats.requestService(lookupEndpoint, LookupRequest("u1"), 5.seconds).either
            _  <- Console.printLine(s"lookup(u1):      $r1") // Right(LookupReply(...))

            r2 <- nats.requestService(lookupEndpoint, LookupRequest("u99"), 5.seconds).either
            _  <- Console.printLine(s"lookup(u99):     $r2") // Left(NotFound("u99"))

            r3 <- nats.requestService(lookupEndpoint, LookupRequest(""), 5.seconds).either
            _  <- Console.printLine(s"lookup(\"\"):      $r3") // Left(ValidationError(...))

          } yield ()
        }
      }
      .provide(Nats.live, NatsConfig.live)
}
