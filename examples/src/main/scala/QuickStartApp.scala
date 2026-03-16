import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig

/**
 * Minimal zio-nats quick-start.
 *
 * Requires a running NATS server: nats-server
 *
 * Run with: sbt "zioNatsExamples/run"
 */
object QuickStartApp extends ZIOAppDefault {

  // ZIOAppDefault.run requires ZIO[Any, Throwable, Unit]; NatsError is not a Throwable subtype.
  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        nats <- Nats.make(NatsConfig.default)

        // Subscribe in the background; take the first 3 messages
        fiber <- nats
                   .subscribe(Subject("greetings"))
                   .take(3)
                   .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}"))
                   .runDrain
                   .fork

        // Give the subscription a moment to register
        _ <- ZIO.sleep(200.millis)

        // Publish 3 messages
        _ <- ZIO.foreachDiscard(1 to 3) { i =>
               nats.publish(Subject("greetings"), s"Hello #$i".toNatsData)
             }

        // Wait for the subscriber fiber to finish
        _ <- fiber.join
      } yield ()
    }.mapError(e => new RuntimeException(e.getMessage))
}
