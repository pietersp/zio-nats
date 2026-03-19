import zio.*
import zio.nats.*

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
 */
object ServiceApp extends ZIOAppDefault {

  // Declare the typed endpoint shape — no handler yet.
  val echoEndpoint = ServiceEndpoint[String, String]("echo")

  val run =
    ZIO
      .serviceWithZIO[Nats] { nats =>
        ZIO.scoped {
          for {
            // 1. Start the service and bind the handler.
            //    The scope manages the service lifetime automatically.
            svc <- nats.service(
                     ServiceConfig(
                       name = "echo-service",
                       version = "1.0.0",
                       description = Some("Echoes every request back to the caller")
                     ),
                     echoEndpoint.implement(payload => ZIO.succeed(payload))
                   )

            _ <- Console.printLine(s"Service '${svc.name}' started [id=${svc.id}]")

            // Give the service a moment to register subscriptions.
            _ <- ZIO.sleep(200.millis)

            // 2. Call the service as a regular NATS client.
            reply1 <- nats.request[String, String](Subject("echo"), "hello world", 5.seconds)
            _      <- Console.printLine(s"Reply: ${reply1.value}")

            reply2 <- nats.request[String, String](Subject("echo"), "ZIO + NATS", 5.seconds)
            _      <- Console.printLine(s"Reply: ${reply2.value}")

            // 3. Query via the discovery API.
            discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
            pings     <- discovery.ping("echo-service")
            _         <- Console.printLine(s"\nDiscovered ${pings.size} instance(s):")
            _         <- ZIO.foreachDiscard(pings) { p =>
                   Console.printLine(s"  ${p.name} v${p.version} [${p.id}]")
                 }

            infos <- discovery.info("echo-service")
            _     <-
              ZIO.foreachDiscard(infos) { i =>
                Console.printLine(s"  Description: ${i.description.getOrElse("none")}") *>
                  ZIO.foreach(i.endpoints) { ep =>
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

          } yield ()
        }
      }
      .provide(Nats.live, NatsConfig.live)
}
