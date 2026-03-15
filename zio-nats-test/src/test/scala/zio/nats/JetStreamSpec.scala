package zio.nats

import io.nats.client.api._
import io.nats.client.{PublishOptions, FetchConsumeOptions}
import zio._
import zio.test._
import zio.test.TestAspect._
import zio.nats.testkit.NatsTestLayers
import zio.nats.subject.Subject

object JetStreamSpec extends ZIOSpecDefault {

  private def createStream(
    jsm: JetStreamManagement,
    name: String,
    subjects: String*
  ): IO[NatsError, StreamInfo] = {
    val config = StreamConfiguration.builder()
      .name(name)
      .subjects(subjects: _*)
      .storageType(StorageType.Memory)
      .build()
    jsm.addStream(config)
  }

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
          info.getConfiguration.getName == "mgmt-stream",
          names.contains("mgmt-stream"),
          !names2.contains("mgmt-stream")
        )
      },

      test("add and list consumers") {
        for {
          jsm        <- ZIO.service[JetStreamManagement]
          _          <- createStream(jsm, "cons-mgmt-stream", "cmgmt.>")
          consConfig  = ConsumerConfiguration.builder()
                          .durable("my-consumer")
                          .filterSubject("cmgmt.>")
                          .build()
          info       <- jsm.addOrUpdateConsumer("cons-mgmt-stream", consConfig)
          names      <- jsm.getConsumerNames("cons-mgmt-stream")
          _          <- jsm.deleteStream("cons-mgmt-stream")
        } yield assertTrue(
          info.getName == "my-consumer",
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
          ack.getStream == "pub-stream",
          ack.getSeqno == 1L
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
          ack.getStream == "async-stream",
          ack.getSeqno == 1L
        )
      },

      test("duplicate detection via message ID") {
        for {
          jsm  <- ZIO.service[JetStreamManagement]
          js   <- ZIO.service[JetStream]
          _    <- createStream(jsm, "dedup-stream", "dedup.>")
          opts  = PublishOptions.builder().messageId("msg-1").build()
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
          jsm        <- ZIO.service[JetStreamManagement]
          js         <- ZIO.service[JetStream]
          _          <- createStream(jsm, "fetch-stream", "fetch.>")
          consConfig  = ConsumerConfiguration.builder()
                          .durable("fetch-cons")
                          .filterSubject("fetch.>")
                          .build()
          _          <- jsm.addOrUpdateConsumer("fetch-stream", consConfig)
          _          <- js.publish(Subject("fetch.1"), Chunk.fromArray("a".getBytes))
          _          <- js.publish(Subject("fetch.2"), Chunk.fromArray("b".getBytes))
          _          <- js.publish(Subject("fetch.3"), Chunk.fromArray("c".getBytes))
          ctx        <- js.consumerContext("fetch-stream", "fetch-cons")
          opts        = FetchConsumeOptions.builder().maxMessages(3).expiresIn(5000).build()
          msgs       <- JetStreamConsumer.fetch(ctx, opts).tap(_.ack).runCollect
          _          <- jsm.deleteStream("fetch-stream")
        } yield assertTrue(msgs.size == 3)
      },

      test("next() returns a single message") {
        for {
          jsm        <- ZIO.service[JetStreamManagement]
          js         <- ZIO.service[JetStream]
          _          <- createStream(jsm, "next-stream", "next.>")
          consConfig  = ConsumerConfiguration.builder()
                          .durable("next-cons")
                          .filterSubject("next.>")
                          .build()
          _          <- jsm.addOrUpdateConsumer("next-stream", consConfig)
          _          <- js.publish(Subject("next.test"), Chunk.fromArray("single".getBytes))
          ctx        <- js.consumerContext("next-stream", "next-cons")
          msg        <- JetStreamConsumer.next(ctx, 5.seconds)
          _          <- msg.map(_.ack).getOrElse(ZIO.unit)
          _          <- jsm.deleteStream("next-stream")
        } yield assertTrue(
          msg.isDefined,
          msg.get.dataAsString == "single"
        )
      },

      test("consume stream delivers messages via callback") {
        for {
          jsm        <- ZIO.service[JetStreamManagement]
          js         <- ZIO.service[JetStream]
          _          <- createStream(jsm, "consume-stream", "consume.>")
          consConfig  = ConsumerConfiguration.builder()
                          .durable("consume-cons")
                          .filterSubject("consume.>")
                          .build()
          _          <- jsm.addOrUpdateConsumer("consume-stream", consConfig)
          _          <- js.publish(Subject("consume.1"), Chunk.fromArray("x".getBytes))
          _          <- js.publish(Subject("consume.2"), Chunk.fromArray("y".getBytes))
          ctx        <- js.consumerContext("consume-stream", "consume-cons")
          msgs       <- JetStreamConsumer.consume(ctx)
                          .tap(_.ack)
                          .take(2)
                          .runCollect
          _          <- jsm.deleteStream("consume-stream")
        } yield assertTrue(msgs.size == 2)
      }
    )

  ).provideShared(
    NatsTestLayers.nats,
    JetStreamManagement.live,
    JetStream.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
