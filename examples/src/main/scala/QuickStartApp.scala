import zio._
import zio.nats._
import zio.nats.config.NatsConfig

/** Minimal zio-nats quick-start.
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
        fiber <- nats.subscribe("greetings")
                   .take(3)
                   .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}"))
                   .runDrain
                   .fork

        // Give the subscription a moment to register
        _ <- ZIO.sleep(200.millis)

        // Publish 3 messages
        _ <- ZIO.foreach(1 to 3)(i =>
               nats.publish("greetings", s"Hello #$i".toNatsData)
             )

        // Wait for the subscriber fiber to finish
        _ <- fiber.join
      } yield ()
    }.mapError(e => new RuntimeException(e.getMessage))
}
