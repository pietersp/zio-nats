package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object NatsRpcSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("NatsRpc")(

    test("respond handles a typed request and sends a typed reply") {
      ZIO.scoped {
        for {
          nats  <- ZIO.service[Nats]
          _     <- NatsRpc.respond[String, String](Subject("rpc.echo")) { req =>
                     ZIO.succeed(s"echo:$req")
                   }
          _     <- ZIO.sleep(300.millis)
          reply <- nats.request[String, String](Subject("rpc.echo"), "hello", 5.seconds)
        } yield assertTrue(reply.value == "echo:hello")
      }
    },

    test("respond silently ignores messages with no replyTo") {
      ZIO.scoped {
        for {
          nats  <- ZIO.service[Nats]
          _     <- NatsRpc.respond[String, String](Subject("rpc.noreply")) { _ =>
                     ZIO.succeed("response")
                   }
          _     <- ZIO.sleep(300.millis)
          // Publish without a reply-to subject (fire-and-forget)
          _     <- nats.publish(Subject("rpc.noreply"), "ignored-message")
          _     <- ZIO.sleep(200.millis)
          // Responder should still be alive; a proper request should succeed
          reply <- nats.request[String, String](Subject("rpc.noreply"), "ping", 5.seconds)
        } yield assertTrue(reply.value == "response")
      }
    },

    test("respond sends no reply when decoding fails") {
      // A codec that fails to decode the sentinel "bad" payload but succeeds for all others
      given badDecodeCodec: NatsCodec[String] = new NatsCodec[String] {
        private val utf8 = java.nio.charset.StandardCharsets.UTF_8
        def encode(a: String): Chunk[Byte] = Chunk.fromArray(a.getBytes(utf8))
        def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, String] = {
          val s = new String(bytes.toArray, utf8)
          if (s == "bad") Left(NatsDecodeError("intentional decode failure"))
          else Right(s)
        }
      }
      ZIO.scoped {
        for {
          nats      <- ZIO.service[Nats]
          _         <- NatsRpc.respond[String, String](Subject("rpc.decodeerr")) { _ => ZIO.succeed("ok") }
          _         <- ZIO.sleep(300.millis)
          // Publish with an explicit replyTo and verify no reply arrives
          replySubj  = Subject("rpc.decodeerr.inbox")
          received  <- Promise.make[Nothing, Boolean]
          fiber     <- nats.subscribe[Chunk[Byte]](replySubj)
                         .tap(_ => received.succeed(true))
                         .take(1)
                         .runDrain
                         .fork
          _         <- nats.publish(Subject("rpc.decodeerr"), "bad", PublishParams(replyTo = Some(replySubj)))
          _         <- ZIO.sleep(500.millis)
          gotReply  <- received.poll.map(_.isDefined)
          _         <- fiber.interrupt
          // Responder must still be alive — a valid request should succeed
          validReply <- nats.request[String, String](Subject("rpc.decodeerr"), "hello", 5.seconds)
        } yield assertTrue(!gotReply) && assertTrue(validReply.value == "ok")
      }
    },

    test("respond sends no reply when the handler fails") {
      ZIO.scoped {
        for {
          nats      <- ZIO.service[Nats]
          attempts  <- zio.Ref.make(0)
          _         <- NatsRpc.respond[String, String](Subject("rpc.handlererr")) { req =>
                         attempts.updateAndGet(_ + 1).flatMap { n =>
                           if (n == 1) ZIO.fail(NatsError.GeneralError("handler boom", new RuntimeException("boom")))
                           else ZIO.succeed(s"echo:$req")
                         }
                       }
          _         <- ZIO.sleep(300.millis)
          // Publish with an explicit replyTo and verify no reply arrives
          replySubj  = Subject("rpc.handlererr.inbox")
          received  <- Promise.make[Nothing, Boolean]
          fiber     <- nats.subscribe[Chunk[Byte]](replySubj)
                         .tap(_ => received.succeed(true))
                         .take(1)
                         .runDrain
                         .fork
          _         <- nats.publish(Subject("rpc.handlererr"), "trigger", PublishParams(replyTo = Some(replySubj)))
          _         <- ZIO.sleep(500.millis)
          gotReply  <- received.poll.map(_.isDefined)
          _         <- fiber.interrupt
          // Responder must still be alive — a valid request should succeed
          validReply <- nats.request[String, String](Subject("rpc.handlererr"), "ping", 5.seconds)
        } yield assertTrue(!gotReply) && assertTrue(validReply.value == "echo:ping")
      }
    }

  ).provideShared(
    NatsTestLayers.nats
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
