import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig

/**
 * Minimal zio-nats quick-start using the ZIO environment pattern.
 *
 * Obtain the `Nats` service via `ZIO.service[Nats]` and call methods directly
 * on the instance.
 *
 * `subscribe[String]` decodes each message payload as a UTF-8 string using the
 * built-in `NatsCodec[String]`. The resulting `Envelope[String]` holds both the
 * decoded value (`env.value`) and the raw `NatsMessage` (`env.message`) for
 * header and subject access.
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
                 .subscribe[String](Subject("greetings"))
                 .take(3)
                 .tap(env => Console.printLine(s"Received: ${env.value}").orDie)
                 .runDrain
                 .fork

      _ <- ZIO.sleep(200.millis)

      // Publish 3 messages — NatsCodec[String] encodes to UTF-8 bytes
      _ <- ZIO.foreachDiscard(1 to 3) { i =>
             nats.publish(Subject("greetings"), s"Hello #$i")
           }

      _ <- fiber.join
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
