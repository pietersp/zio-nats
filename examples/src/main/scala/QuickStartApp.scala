import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig

/**
 * Minimal zio-nats quick-start using the ZIO environment pattern.
 *
 * The Nats companion accessors (Nats.publish, Nats.subscribe, etc.) accept Nats
 * from the ZIO environment and route raw subscribe through an unambiguous
 * internal helper, so no type annotations are needed at call sites.
 *
 * Requires a running NATS server: nats-server
 *
 * Run with: sbt "zioNatsExamples/run"
 */
object QuickStartApp extends ZIOAppDefault {

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      // Nats.subscribeRaw has a unique name — no overload ambiguity.
      fiber <- Nats
                 .subscribeRaw(Subject("greetings"))
                 .take(3)
                 .tap(msg => Console.printLine(s"Received: ${msg.dataAsString}").orDie)
                 .runDrain
                 .fork

      _ <- ZIO.sleep(200.millis)

      // Publish 3 messages
      _ <- ZIO.foreachDiscard(1 to 3) { i =>
             Nats.publish(Subject("greetings"), s"Hello #$i".toNatsData)
           }

      _ <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
