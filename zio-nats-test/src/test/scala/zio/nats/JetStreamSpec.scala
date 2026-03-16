package zio.nats

import zio.*
import zio.nats.subject.Subject
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object JetStreamSpec extends ZIOSpecDefault {

  private def createStream(
    jsm: JetStreamManagement,
    name: String,
    subjects: String*
  ): IO[NatsError, StreamSummary] =
    jsm.addStream(StreamConfig(name = name, subjects = subjects.toList, storageType = StorageType.Memory))

  def spec: Spec[Any, Throwable] = suite("JetStream")(

    suite("Management")(

      test("create, list, and delete a stream") {
        for {
          jsm    <- ZIO.service[JetStreamManagement]
          info   <- createStream(jsm, "mgmt-stream", "mgmt.>")
          names  <- jsm.getStreamNames
          _      <- jsm.deleteStream("mgmt-stream")
          names2 <- jsm.getStreamNames
        } yield assertTrue(
          info.name == "mgmt-stream",
          names.contains("mgmt-stream"),
          !names2.contains("mgmt-stream")
        )
      },

      test("add and list consumers") {
        for {
          jsm  <- ZIO.service[JetStreamManagement]
          _    <- createStream(jsm, "cons-mgmt-stream", "cmgmt.>")
          info <- jsm.addOrUpdateConsumer(
                    "cons-mgmt-stream",
                    ConsumerConfig.durable("my-consumer").copy(filterSubject = Some("cmgmt.>"))
                  )
          names <- jsm.getConsumerNames("cons-mgmt-stream")
          _     <- jsm.deleteStream("cons-mgmt-stream")
        } yield assertTrue(
          info.name == "my-consumer",
          names.contains("my-consumer")
        )
      }
    ),

    suite("Publishing")(

      test("publish and receive a server ack") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "pub-stream", "pub.>")
          ack <- js.publish(Subject("pub.test"), Chunk.fromArray("hello-js".getBytes))
          _   <- jsm.deleteStream("pub-stream")
        } yield assertTrue(
          ack.stream == "pub-stream",
          ack.seqno == 1L
        )
      },

      test("publish async and await ack") {
        for {
          jsm     <- ZIO.service[JetStreamManagement]
          js      <- ZIO.service[JetStream]
          _       <- createStream(jsm, "async-stream", "async.>")
          ackTask <- js.publishAsync(Subject("async.test"), Chunk.fromArray("async".getBytes))
          ack     <- ackTask
          _       <- jsm.deleteStream("async-stream")
        } yield assertTrue(
          ack.stream == "async-stream",
          ack.seqno == 1L
        )
      },

      test("duplicate detection via message ID") {
        for {
          jsm  <- ZIO.service[JetStreamManagement]
          js   <- ZIO.service[JetStream]
          _    <- createStream(jsm, "dedup-stream", "dedup.>")
          opts  = PublishOptions(messageId = Some("msg-1"))
          ack1 <- js.publish(Subject("dedup.test"), Chunk.fromArray("first".getBytes), opts)
          ack2 <- js.publish(Subject("dedup.test"), Chunk.fromArray("first".getBytes), opts)
          _    <- jsm.deleteStream("dedup-stream")
        } yield assertTrue(
          !ack1.isDuplicate,
          ack2.isDuplicate
        )
      }
    ),

    suite("Consuming (Simplified API)")(

      test("fetch a batch of messages") {
        for {
          jsm      <- ZIO.service[JetStreamManagement]
          js       <- ZIO.service[JetStream]
          _        <- createStream(jsm, "fetch-stream", "fetch.>")
          _        <- jsm.addOrUpdateConsumer(
                        "fetch-stream",
                        ConsumerConfig.durable("fetch-cons").copy(filterSubject = Some("fetch.>"))
                      )
          _        <- js.publish(Subject("fetch.1"), Chunk.fromArray("a".getBytes))
          _        <- js.publish(Subject("fetch.2"), Chunk.fromArray("b".getBytes))
          _        <- js.publish(Subject("fetch.3"), Chunk.fromArray("c".getBytes))
          consumer <- js.consumer("fetch-stream", "fetch-cons")
          msgs     <- consumer.fetch(FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                        .tap(_.ack)
                        .runCollect
          _        <- jsm.deleteStream("fetch-stream")
        } yield assertTrue(msgs.size == 3)
      },

      test("next() returns a single message") {
        for {
          jsm      <- ZIO.service[JetStreamManagement]
          js       <- ZIO.service[JetStream]
          _        <- createStream(jsm, "next-stream", "next.>")
          _        <- jsm.addOrUpdateConsumer(
                        "next-stream",
                        ConsumerConfig.durable("next-cons").copy(filterSubject = Some("next.>"))
                      )
          _        <- js.publish(Subject("next.test"), Chunk.fromArray("single".getBytes))
          consumer <- js.consumer("next-stream", "next-cons")
          msg      <- consumer.next(5.seconds)
          _        <- msg.map(_.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("next-stream")
        } yield assertTrue(
          msg.isDefined,
          msg.get.dataAsString == "single"
        )
      },

      test("consume stream delivers messages via callback") {
        for {
          jsm      <- ZIO.service[JetStreamManagement]
          js       <- ZIO.service[JetStream]
          _        <- createStream(jsm, "consume-stream", "consume.>")
          _        <- jsm.addOrUpdateConsumer(
                        "consume-stream",
                        ConsumerConfig.durable("consume-cons").copy(filterSubject = Some("consume.>"))
                      )
          _        <- js.publish(Subject("consume.1"), Chunk.fromArray("x".getBytes))
          _        <- js.publish(Subject("consume.2"), Chunk.fromArray("y".getBytes))
          consumer <- js.consumer("consume-stream", "consume-cons")
          msgs     <- consumer.consume()
                        .tap(_.ack)
                        .take(2)
                        .runCollect
          _        <- jsm.deleteStream("consume-stream")
        } yield assertTrue(msgs.size == 2)
      }
    )

  ).provideShared(
    NatsTestLayers.nats,
    JetStreamManagement.live,
    JetStream.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
