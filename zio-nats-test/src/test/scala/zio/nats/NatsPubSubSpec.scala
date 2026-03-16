package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object NatsPubSubSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Nats Pub/Sub")(
    test("publish and subscribe to a subject") {
      val subject = Subject("test.pubsub")

      for {
        nats     <- ZIO.service[Nats]
        received <- Promise.make[Nothing, NatsMessage]
        fiber    <- nats
                   .subscribeRaw(subject)
                   .tap(msg => received.succeed(msg))
                   .take(1)
                   .runDrain
                   .fork
        _   <- ZIO.sleep(500.millis)
        _   <- nats.publish(subject, Chunk.fromArray("hello".getBytes))
        msg <- received.await
        _   <- fiber.interrupt
      } yield assertTrue(
        msg.subject == subject,
        msg.dataAsString == "hello"
      )
    },

    test("publish and subscribe with headers") {
      for {
        nats     <- ZIO.service[Nats]
        received <- Promise.make[Nothing, NatsMessage]
        fiber    <- nats
                   .subscribeRaw(Subject("test.headers"))
                   .tap(msg => received.succeed(msg))
                   .take(1)
                   .runDrain
                   .fork
        _ <- ZIO.sleep(500.millis)
        _ <- nats.publish(
               Subject("test.headers"),
               Chunk.fromArray("with-headers".getBytes),
               PublishParams(headers = Headers("X-Custom" -> "value1"))
             )
        msg <- received.await
        _   <- fiber.interrupt
      } yield assertTrue(
        msg.headers.get("X-Custom") == Chunk("value1"),
        msg.dataAsString == "with-headers"
      )
    },

    test("request-reply pattern") {
      val subject = Subject("test.request")
      for {
        nats  <- ZIO.service[Nats]
        fiber <- nats
                   .subscribeRaw(subject)
                   .tap { msg =>
                     msg.replyTo match {
                       case Some(reply) =>
                         nats.publish(reply, Chunk.fromArray("pong".getBytes))
                       case None => ZIO.unit
                     }
                   }
                   .take(1)
                   .runDrain
                   .fork
        _     <- ZIO.sleep(500.millis)
        reply <- nats.request(subject, Chunk.fromArray("ping".getBytes), 5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(reply.dataAsString == "pong")
    },

    test("queue group delivers to exactly one subscriber") {
      val subject = Subject("test.queue")
      for {
        nats    <- ZIO.service[Nats]
        counter <- Ref.make(0)
        latch   <- Promise.make[Nothing, Unit]
        fiber1  <- nats
                    .subscribe(subject, QueueGroup("group1"))
                    .tap(_ => counter.update(_ + 1) *> latch.succeed(()))
                    .take(1)
                    .runDrain
                    .fork
        fiber2 <- nats
                    .subscribe(subject, QueueGroup("group1"))
                    .tap(_ => counter.update(_ + 1) *> latch.succeed(()))
                    .take(1)
                    .runDrain
                    .fork
        _     <- ZIO.sleep(500.millis)
        _     <- nats.publish(subject, Chunk.fromArray("queued".getBytes))
        _     <- latch.await
        _     <- ZIO.sleep(200.millis)
        count <- counter.get
        _     <- fiber1.interrupt
        _     <- fiber2.interrupt
      } yield assertTrue(count == 1)
    }
  ).provideShared(
    NatsTestLayers.nats
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
