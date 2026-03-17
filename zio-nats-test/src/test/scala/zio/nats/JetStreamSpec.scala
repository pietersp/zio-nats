package zio.nats

import zio.*
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

      test("maxMsgs limits the number of retained messages") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(
                   StreamConfig(
                     name = "maxmsgs-stream",
                     subjects = List("maxmsgs.>"),
                     storageType = StorageType.Memory,
                     maxMsgs = 2
                   )
                 )
          _ <- js.publish(Subject("maxmsgs.1"), "a")
          _ <- js.publish(Subject("maxmsgs.2"), "b")
          _ <- js.publish(Subject("maxmsgs.3"), "c")
          info <- jsm.getStreamInfo("maxmsgs-stream")
          _    <- jsm.deleteStream("maxmsgs-stream")
        } yield assertTrue(info.messageCount == 2L)
      },

      test("maxMsgsPerSubject caps messages per subject") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(
                   StreamConfig(
                     name = "maxpersub-stream",
                     subjects = List("maxpersub.>"),
                     storageType = StorageType.Memory,
                     maxMsgsPerSubject = 1
                   )
                 )
          _ <- js.publish(Subject("maxpersub.a"), "v1")
          _ <- js.publish(Subject("maxpersub.a"), "v2")
          _ <- js.publish(Subject("maxpersub.b"), "v1")
          info <- jsm.getStreamInfo("maxpersub-stream")
          _    <- jsm.deleteStream("maxpersub-stream")
        } yield assertTrue(info.messageCount == 2L)
      },

      test("duplicateWindow controls the dedup window") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(
                   StreamConfig(
                     name = "dupewin-stream",
                     subjects = List("dupewin.>"),
                     storageType = StorageType.Memory,
                     duplicateWindow = Some(30.seconds)
                   )
                 )
          opts  = JsPublishParams(options = Some(PublishOptions(messageId = Some("dw-1"))))
          ack1 <- js.publish(Subject("dupewin.test"), "first", opts)
          ack2 <- js.publish(Subject("dupewin.test"), "first", opts)
          _    <- jsm.deleteStream("dupewin-stream")
        } yield assertTrue(!ack1.isDuplicate, ack2.isDuplicate)
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
          opts  = JsPublishParams(options = Some(PublishOptions(messageId = Some("msg-1"))))
          ack1 <- js.publish(Subject("dedup.test"), Chunk.fromArray("first".getBytes), opts)
          ack2 <- js.publish(Subject("dedup.test"), Chunk.fromArray("first".getBytes), opts)
          _    <- jsm.deleteStream("dedup-stream")
        } yield assertTrue(
          !ack1.isDuplicate,
          ack2.isDuplicate
        )
      }
    ),

    suite("Consumer pause and resume")(
      test("pause and resume a consumer") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          _   <- jsm.addStream(StreamConfig("pause-stream", subjects = List("pause.>"), storageType = StorageType.Memory))
          _   <- jsm.addOrUpdateConsumer("pause-stream", ConsumerConfig.durable("pause-cons"))
          pauseUntil = java.time.ZonedDateTime.now().plusSeconds(30)
          pauseInfo <- jsm.pauseConsumer("pause-stream", "pause-cons", pauseUntil)
          resumed   <- jsm.resumeConsumer("pause-stream", "pause-cons")
          _         <- jsm.deleteStream("pause-stream")
        } yield assertTrue(pauseInfo.isPaused, resumed)
      }
    ),

    suite("Ordered Consumer")(
      test("fetch messages in order") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(StreamConfig("ord-stream", subjects = List("ord.>"), storageType = StorageType.Memory))
          _   <- js.publish(Subject("ord.1"), "a")
          _   <- js.publish(Subject("ord.2"), "b")
          _   <- js.publish(Subject("ord.3"), "c")
          oc  <- js.orderedConsumer("ord-stream", OrderedConsumerConfig())
          msgs <- oc
                    .fetch(FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                    .runCollect
          _ <- jsm.deleteStream("ord-stream")
        } yield assertTrue(
          msgs.size == 3,
          msgs.map(_.dataAsString).toList == List("a", "b", "c")
        )
      },

      test("ordered consumer with filter subject") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(StreamConfig("ord-filter-stream", subjects = List("ordf.>"), storageType = StorageType.Memory))
          _   <- js.publish(Subject("ordf.x"), "x-msg")
          _   <- js.publish(Subject("ordf.y"), "y-msg")
          oc  <- js.orderedConsumer("ord-filter-stream", OrderedConsumerConfig(filterSubjects = List("ordf.x")))
          msg <- oc.next(5.seconds)
          _   <- jsm.deleteStream("ord-filter-stream")
        } yield assertTrue(msg.exists(_.dataAsString == "x-msg"))
      }
    ),

    suite("Consuming (Simplified API)")(
      test("fetch a batch of messages") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "fetch-stream", "fetch.>")
          _   <- jsm.addOrUpdateConsumer(
                 "fetch-stream",
                 ConsumerConfig.durable("fetch-cons").copy(filterSubject = Some("fetch.>"))
               )
          _        <- js.publish(Subject("fetch.1"), Chunk.fromArray("a".getBytes))
          _        <- js.publish(Subject("fetch.2"), Chunk.fromArray("b".getBytes))
          _        <- js.publish(Subject("fetch.3"), Chunk.fromArray("c".getBytes))
          consumer <- js.consumer("fetch-stream", "fetch-cons")
          msgs     <- consumer
                    .fetch(FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                    .tap(_.ack)
                    .runCollect
          _ <- jsm.deleteStream("fetch-stream")
        } yield assertTrue(msgs.size == 3)
      },

      test("next() returns a single message") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "next-stream", "next.>")
          _   <- jsm.addOrUpdateConsumer(
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
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "consume-stream", "consume.>")
          _   <- jsm.addOrUpdateConsumer(
                 "consume-stream",
                 ConsumerConfig.durable("consume-cons").copy(filterSubject = Some("consume.>"))
               )
          _        <- js.publish(Subject("consume.1"), Chunk.fromArray("x".getBytes))
          _        <- js.publish(Subject("consume.2"), Chunk.fromArray("y".getBytes))
          consumer <- js.consumer("consume-stream", "consume-cons")
          msgs     <- consumer
                    .consume()
                    .tap(_.ack)
                    .take(2)
                    .runCollect
          _ <- jsm.deleteStream("consume-stream")
        } yield assertTrue(msgs.size == 2)
      }
    )
  ).provideShared(
    NatsTestLayers.nats,
    JetStreamManagement.live,
    JetStream.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
