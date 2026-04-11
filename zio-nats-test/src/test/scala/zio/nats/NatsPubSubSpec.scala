package zio.nats

import io.nats.client.Connection.Status
import zio.*
import zio.nats.testkit.{NatsContainer, NatsTestLayers}
import zio.test.*
import zio.test.TestAspect.*

object NatsPubSubSpec extends ZIOSpecDefault {

  private def awaitClosedStatus(
    conn: io.nats.client.Connection,
    remainingPolls: Int
  ): UIO[Status] =
    ZIO.succeed(conn.getStatus).flatMap { status =>
      if (status == Status.CLOSED || remainingPolls <= 0) ZIO.succeed(status)
      else ZIO.sleep(100.millis) *> awaitClosedStatus(conn, remainingPolls - 1)
    }

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
        // flush ensures the SUBSCRIBE command has been transmitted to the server
        _   <- ZIO.sleep(100.millis)
        _   <- nats.flush(5.seconds)
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
        _ <- nats.flush(5.seconds)
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
        _     <- nats.flush(5.seconds)
        reply <- nats.request[Chunk[Byte], String](subject, Chunk.fromArray("ping".getBytes), 5.seconds)
        _     <- fiber.interrupt
      } yield assertTrue(reply.value == "pong")
    },

    test("request sends headers that the subscriber receives") {
      val subject = Subject("test.request.headers")
      for {
        nats         <- ZIO.service[Nats]
        headersLatch <- Promise.make[Nothing, Headers]
        fiber        <- nats
                   .subscribe[Chunk[Byte]](subject)
                   .tap { env =>
                     val capture = headersLatch.succeed(env.message.headers)
                     env.message.replyTo match {
                       case Some(reply) =>
                         capture *> nats.publish(reply, Chunk.fromArray("pong".getBytes))
                       case None => capture.unit
                     }
                   }
                   .take(1)
                   .runDrain
                   .fork
        _ <- nats.flush(5.seconds)
        _ <- nats.request[Chunk[Byte], Chunk[Byte]](
               subject,
               Chunk.fromArray("ping".getBytes),
               5.seconds,
               PublishParams(headers = Headers("X-Version" -> "2"))
             )
        received <- headersLatch.await
        _        <- fiber.interrupt
      } yield assertTrue(received.get("X-Version") == Chunk("2"))
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
        // flush ensures both SUBSCRIBE commands have been transmitted to the server
        _ <- nats.flush(5.seconds)
        _ <- nats.publish(subject, Chunk.fromArray("queued".getBytes))
        _ <- latch.await
        // Wait briefly to confirm no second delivery arrives
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
        firstMsg  <- Promise.make[Nothing, Unit]
        // Run a scoped subscriber indefinitely so drain is triggered on scope exit (interrupt)
        subscriberFiber <- ZIO.scoped {
                             Nats.make(natsConfig).flatMap { subNats =>
                               subNats
                                 .subscribe[Chunk[Byte]](subject)
                                 .tap { env =>
                                   received.update(_ :+ env.message.dataAsString) *>
                                     firstMsg.succeed(())
                                 }
                                 .runDrain
                             }
                           }.fork
        // Publish a probe message repeatedly until the subscriber confirms receipt,
        // which proves the subscription is active before we proceed
        _ <- (nats.publish(subject, Chunk.fromArray("probe".getBytes)) *>
               firstMsg.await.timeout(100.millis)).repeatUntil(_.isDefined)
        // Interrupt the subscriber fiber — scope exits and drain is triggered automatically
        _    <- subscriberFiber.interrupt
        msgs <- received.get
        // Subscriber was active and received messages; if drain threw, the test would fail
      } yield assertTrue(msgs.nonEmpty)
    },

    test("scope exit transitions the underlying jnats connection to CLOSED") {
      val subject = Subject("drain.thread-cleanup")

      for {
        nats        <- ZIO.service[Nats]
        container   <- ZIO.service[NatsContainer]
        received    <- Promise.make[Nothing, Unit]
        connRef     <- Ref.make(Option.empty[io.nats.client.Connection])
        scopedFiber <- ZIO.scoped {
                         Nats
                           .make(NatsConfig(servers = List(container.clientUrl), drainTimeout = 2.seconds))
                           .flatMap { scopedNats =>
                             for {
                               conn <- scopedNats.underlying
                               _    <- connRef.set(Some(conn))
                               _    <- scopedNats
                                      .subscribe[Chunk[Byte]](subject)
                                      .tap(_ => received.succeed(()))
                                      .runDrain
                             } yield ()
                           }
                       }.fork
        _ <- (nats.publish(subject, Chunk.fromArray("probe".getBytes)) *>
               received.await.timeout(100.millis)).repeatUntil(_.isDefined)
        conn        <- connRef.get.someOrFailException
        _           <- scopedFiber.interrupt
        finalStatus <- awaitClosedStatus(conn, 50)
      } yield assertTrue(finalStatus == Status.CLOSED)
    }
  ).provideShared(
    NatsTestLayers.container,
    NatsTestLayers.nats
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
