package zio.nats

import zio.*
import zio.nats.subject.Subject
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object NatsPubSubSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Nats Pub/Sub")(
    test("publish and subscribe to a subject") {
      for {
        nats     <- ZIO.service[Nats]
        received <- Promise.make[Nothing, NatsMessage]
        fiber    <- nats
                   .subscribe(Subject("test.pubsub"))
                   .tap(msg => received.succeed(msg))
                   .take(1)
                   .runDrain
                   .fork
        _   <- ZIO.sleep(500.millis)
        _   <- nats.publish(Subject("test.pubsub"), Chunk.fromArray("hello".getBytes))
        msg <- received.await
        _   <- fiber.interrupt
      } yield assertTrue(
        msg.subject == "test.pubsub",
        msg.dataAsString == "hello"
      )
    },

    test("publish and subscribe with headers") {
      for {
        nats     <- ZIO.service[Nats]
        received <- Promise.make[Nothing, NatsMessage]
        fiber    <- nats
                   .subscribe(Subject("test.headers"))
                   .tap(msg => received.succeed(msg))
                   .take(1)
                   .runDrain
                   .fork
        _ <- ZIO.sleep(500.millis)
        _ <- nats.publish(
               Subject("test.headers"),
               Chunk.fromArray("with-headers".getBytes),
               Map("X-Custom" -> List("value1"))
             )
        msg <- received.await
        _   <- fiber.interrupt
      } yield assertTrue(
        msg.headers.get("X-Custom").contains(List("value1")),
        msg.dataAsString == "with-headers"
      )
    },

    test("request-reply pattern") {
      for {
        nats  <- ZIO.service[Nats]
        fiber <- nats
                   .subscribe(Subject("test.request"))
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
        reply <- nats.request(Subject("test.request"), Chunk.fromArray("ping".getBytes), 5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(reply.dataAsString == "pong")
    },

    test("queue group delivers to exactly one subscriber") {
      for {
        nats    <- ZIO.service[Nats]
        counter <- Ref.make(0)
        latch   <- Promise.make[Nothing, Unit]
        fiber1  <- nats
                    .subscribe(Subject("test.queue"), Subject("group1"))
                    .tap(_ => counter.update(_ + 1) *> latch.succeed(()))
                    .take(1)
                    .runDrain
                    .fork
        fiber2 <- nats
                    .subscribe(Subject("test.queue"), Subject("group1"))
                    .tap(_ => counter.update(_ + 1) *> latch.succeed(()))
                    .take(1)
                    .runDrain
                    .fork
        _     <- ZIO.sleep(500.millis)
        _     <- nats.publish(Subject("test.queue"), Chunk.fromArray("queued".getBytes))
        _     <- latch.await
        _     <- ZIO.sleep(200.millis)
        count <- counter.get
        _     <- fiber1.interrupt
        _     <- fiber2.interrupt
      } yield assertTrue(count == 1)
    },

    test("connection status is CONNECTED") {
      for {
        nats   <- ZIO.service[Nats]
        status <- nats.status
      } yield assertTrue(status == io.nats.client.Connection.Status.CONNECTED)
    }
  ).provideShared(NatsTestLayers.nats) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
