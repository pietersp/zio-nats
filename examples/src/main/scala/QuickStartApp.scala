import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig

/**
 * Minimal zio-nats quick-start using the ZIO environment pattern.
 *
 * Obtain the `Nats` service via `ZIO.service[Nats]` and call methods
 * directly on the instance.
 *
 * Requires a running NATS server: nats-server
 *
 * Run with: sbt "zioNatsExamples/run"
 */
object QuickStartApp extends ZIOAppDefault {

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats  <- ZIO.service[Nats]
      fiber <- nats
                 .subscribeRaw(Subject("greetings"))
                 .take(3)
                 .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}").orDie)
                 .runDrain
                 .fork

      _ <- ZIO.sleep(200.millis)

      // Publish 3 messages
      _ <- ZIO.foreachDiscard(1 to 3) { i =>
             nats.publish(Subject("greetings"), s"Hello #$i".toNatsData)
           }

      _ <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
