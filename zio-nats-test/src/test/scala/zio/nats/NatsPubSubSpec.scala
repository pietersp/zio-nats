package zio.nats

import zio.*
import zio.nats.testkit.{NatsContainer, NatsTestLayers}
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
                   .subscribe[Chunk[Byte]](subject)
                   .tap(env => received.succeed(env.message))
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
                   .subscribe[Chunk[Byte]](Subject("test.headers"))
                   .tap(env => received.succeed(env.message))
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
                   .subscribe[Chunk[Byte]](subject)
                   .tap { env =>
                     env.message.replyTo match {
                       case Some(reply) =>
                         nats.publish(reply, Chunk.fromArray("pong".getBytes))
                       case None => ZIO.unit
                     }
                   }
                   .take(1)
                   .runDrain
                   .fork
        _     <- ZIO.sleep(500.millis)
        reply <- nats.request[Chunk[Byte], String](subject, Chunk.fromArray("ping".getBytes), 5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(reply.value == "pong")
    },

    test("rtt returns a positive duration for an active connection") {
      for {
        nats <- ZIO.service[Nats]
        d    <- nats.rtt
      } yield assertTrue(d.toNanos > 0)
    },

    test("connectedUrl returns the NATS server URL while connected") {
      for {
        nats <- ZIO.service[Nats]
        url  <- nats.connectedUrl
      } yield assertTrue(url.exists(_.startsWith("nats://")))
    },

    test("statistics tracks outgoing messages") {
      for {
        nats   <- ZIO.service[Nats]
        before <- nats.statistics
        _      <- nats.publish(Subject("stats.test"), Chunk.fromArray("x".getBytes))
        after  <- nats.statistics
      } yield assertTrue(after.outMsgs > before.outMsgs)
    },

    test("outgoingPendingMessageCount and bytes drop to zero after flush") {
      for {
        nats  <- ZIO.service[Nats]
        _     <- nats.publish(Subject("pending.test"), Chunk.fromArray("x".getBytes))
        _     <- nats.flush(5.seconds)
        msgs  <- nats.outgoingPendingMessageCount
        bytes <- nats.outgoingPendingBytes
      } yield assertTrue(msgs == 0L, bytes == 0L)
    },

    test("flush(timeout) drains the outgoing message queue") {
      for {
        nats <- ZIO.service[Nats]
        _    <- nats.publish(Subject("flush.test"), Chunk.fromArray("x".getBytes))
        _    <- nats.flush(5.seconds)
        msgs <- nats.outgoingPendingMessageCount
      } yield assertTrue(msgs == 0L)
    },

    test("serverInfo returns a non-empty server name and version") {
      for {
        nats <- ZIO.service[Nats]
        info <- nats.serverInfo
      } yield assertTrue(info.serverName.nonEmpty, info.version.nonEmpty, info.port > 0)
    },

    test("status returns Connected while the connection is active") {
      for {
        nats   <- ZIO.service[Nats]
        status <- nats.status
      } yield assertTrue(status == ConnectionStatus.Connected)
    },

    test("queue group delivers to exactly one subscriber") {
      val subject = Subject("test.queue")
      for {
        nats    <- ZIO.service[Nats]
        counter <- Ref.make(0)
        latch   <- Promise.make[Nothing, Unit]
        fiber1  <- nats
                    .subscribe[Chunk[Byte]](subject, Some(QueueGroup("group1")))
                    .tap(_ => counter.update(_ + 1) *> latch.succeed(()))
                    .take(1)
                    .runDrain
                    .fork
        fiber2 <- nats
                    .subscribe[Chunk[Byte]](subject, Some(QueueGroup("group1")))
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
    },

    test("connection drains gracefully on scope exit") {
      val subject = Subject("drain.test")
      for {
        nats      <- ZIO.service[Nats]
        container <- ZIO.service[NatsContainer]
        natsConfig = NatsConfig(servers = List(container.clientUrl), drainTimeout = 2.seconds)
        received  <- Ref.make(List.empty[String])
        // Start a subscriber that runs indefinitely (no take), so it keeps receiving
        // messages until we interrupt it. When the fiber is interrupted, the scope
        // ends and drain is triggered automatically.
        subscriberFiber <- ZIO.scoped {
                             Nats.make(natsConfig).flatMap { subNats =>
                               subNats
                                 .subscribe[Chunk[Byte]](subject)
                                 .tap(env => received.update(_ :+ env.message.dataAsString))
                                 .runDrain
                             }
                           }.fork
        // Wait for subscription to be active and registered with the server
        _ <- ZIO.sleep(1.second)
        // Publish messages while subscriber is running
        _ <- ZIO.foreach(1 to 10)(i => nats.publish(subject, Chunk.fromArray(s"msg$i".getBytes)))
        // Give time for messages to be received
        _ <- ZIO.sleep(500.millis)
        // Interrupt the subscriber fiber - this triggers the scoped effect to be interrupted,
        // which ends the scope and automatically triggers drain on the connection
        _ <- subscriberFiber.interrupt
        // Give drain time to complete (up to the 2 second timeout)
        _    <- ZIO.sleep(300.millis)
        msgs <- received.get
        // Verify we received some messages before interrupt, and that drain completed
        // without error (verified by no exceptions being thrown)
      } yield assertTrue(msgs.length >= 5, msgs.length <= 10)
    }
  ).provideShared(
    NatsTestLayers.container,
    NatsTestLayers.nats
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
