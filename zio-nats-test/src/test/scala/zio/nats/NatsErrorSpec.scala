package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object NatsErrorSpec extends ZIOSpecDefault {

  /** A NatsCodec[String] whose decode always fails, for error-path testing. */
  private val failingDecodeCodec: NatsCodec[String] = new NatsCodec[String] {
    private val utf8 = java.nio.charset.StandardCharsets.UTF_8
    def encode(a: String): Chunk[Byte] = Chunk.fromArray(a.getBytes(utf8))
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, String] =
      Left(NatsDecodeError("intentional decode failure for error-path test"))
  }

  def spec: Spec[Any, Throwable] = suite("Error surfacing")(

    suite("NatsError.DecodingError")(

      test("subscribe surfaces DecodingError when payload cannot be decoded") {
        for {
          nats  <- ZIO.service[Nats]
          fiber <- {
            given NatsCodec[String] = failingDecodeCodec
            nats
              .subscribe[String](Subject("err.decode.subscribe"))
              .take(1)
              .runDrain
              .either
              .fork
          }
          _   <- ZIO.sleep(300.millis)
          _   <- nats.publish(Subject("err.decode.subscribe"), "trigger")
          res <- fiber.join
        } yield assertTrue(res match {
          case Left(_: NatsError.DecodingError) => true
          case _                                => false
        })
      },

      test("request surfaces DecodingError when reply cannot be decoded") {
        ZIO.scoped {
          for {
            nats <- ZIO.service[Nats]
            _    <- nats
                      .subscribe[Chunk[Byte]](Subject("err.decode.request"))
                      .mapZIO { env =>
                        env.message.replyTo match {
                          case Some(r) => nats.publish(r, Chunk.fromArray("response".getBytes))
                          case None    => ZIO.unit
                        }
                      }
                      .runDrain
                      .forkScoped
            _   <- ZIO.sleep(300.millis)
            res <- {
              given NatsCodec[String] = failingDecodeCodec
              nats.request[String, String](Subject("err.decode.request"), "ping", 5.seconds).either
            }
          } yield assertTrue(res match {
            case Left(_: NatsError.DecodingError) => true
            case _                                => false
          })
        }
      }
    ),

    suite("NatsError.Timeout")(

      test("request times out when no responder is registered") {
        for {
          nats <- ZIO.service[Nats]
          res  <- nats.request[String, String](Subject("err.timeout.noresp"), "ping", 200.millis).either
        } yield assertTrue(res match {
          case Left(_: NatsError.Timeout) => true
          case _                          => false
        })
      }
    ),

    suite("NatsError.JetStreamApiError")(

      test("getStreamInfo fails with JetStreamApiError for a non-existent stream") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          res <- jsm.getStreamInfo("definitely-not-a-stream-xyz").either
        } yield assertTrue(res match {
          case Left(_: NatsError.JetStreamApiError) => true
          case _                                    => false
        })
      },

      test("kv.create fails with JetStreamApiError when key already exists") {
        for {
          kvm <- ZIO.service[KeyValueManagement]
          _   <- kvm.create(KeyValueConfig(name = "err-kv-create", storageType = StorageType.Memory))
          kv  <- KeyValue.bucket("err-kv-create")
          _   <- kv.create("existing-key", "first")
          res <- kv.create("existing-key", "second").either
          _   <- kvm.delete("err-kv-create")
        } yield assertTrue(res match {
          case Left(_: NatsError.JetStreamApiError) => true
          case _                                    => false
        })
      }
    )

  ).provideShared(
    NatsTestLayers.nats,
    JetStreamManagement.live,
    KeyValueManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
