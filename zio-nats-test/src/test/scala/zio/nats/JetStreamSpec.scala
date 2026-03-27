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
          _    <- js.publish(Subject("maxmsgs.1"), "a")
          _    <- js.publish(Subject("maxmsgs.2"), "b")
          _    <- js.publish(Subject("maxmsgs.3"), "c")
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
          _    <- js.publish(Subject("maxpersub.a"), "v1")
          _    <- js.publish(Subject("maxpersub.a"), "v2")
          _    <- js.publish(Subject("maxpersub.b"), "v1")
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
      },

      test("getStreams lists full stream objects") {
        for {
          jsm     <- ZIO.service[JetStreamManagement]
          _       <- createStream(jsm, "getstrs-stream", "getstrs.>")
          streams <- jsm.getStreams
          _       <- jsm.deleteStream("getstrs-stream")
        } yield assertTrue(streams.exists(_.name == "getstrs-stream"))
      },

      test("updateStream changes a stream's configuration") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <-
            jsm.addStream(
              StreamConfig("update-stream", subjects = List("update.>"), storageType = StorageType.Memory, maxMsgs = 5)
            )
          _       <- js.publish(Subject("update.1"), "a")
          _       <- js.publish(Subject("update.2"), "b")
          updated <- jsm.updateStream(
                       StreamConfig(
                         "update-stream",
                         subjects = List("update.>"),
                         storageType = StorageType.Memory,
                         maxMsgs = 100
                       )
                     )
          info <- jsm.getStreamInfo("update-stream")
          _    <- jsm.deleteStream("update-stream")
        } yield assertTrue(updated.name == "update-stream", info.messageCount == 2L)
      },

      test("purgeStream removes all messages") {
        for {
          jsm  <- ZIO.service[JetStreamManagement]
          js   <- ZIO.service[JetStream]
          _    <- createStream(jsm, "purge-all-stream", "purgeall.>")
          _    <- js.publish(Subject("purgeall.1"), "a")
          _    <- js.publish(Subject("purgeall.2"), "b")
          _    <- js.publish(Subject("purgeall.3"), "c")
          _    <- jsm.purgeStream("purge-all-stream")
          info <- jsm.getStreamInfo("purge-all-stream")
          _    <- jsm.deleteStream("purge-all-stream")
        } yield assertTrue(info.messageCount == 0L)
      },

      test("purgeStream by subject with keepLast retains the last message") {
        for {
          jsm    <- ZIO.service[JetStreamManagement]
          js     <- ZIO.service[JetStream]
          _      <- createStream(jsm, "purge-subj-stream", "purgesubj.>")
          _      <- js.publish(Subject("purgesubj.a"), "v1")
          _      <- js.publish(Subject("purgesubj.a"), "v2")
          _      <- js.publish(Subject("purgesubj.a"), "v3")
          result <- jsm.purgeStream("purge-subj-stream", "purgesubj.a", keepLast = Some(1L))
          info   <- jsm.getStreamInfo("purge-subj-stream")
          _      <- jsm.deleteStream("purge-subj-stream")
        } yield assertTrue(result.purgedCount == 2L, info.messageCount == 1L)
      },

      test("deleteConsumer removes a consumer from the stream") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          _   <- createStream(jsm, "delcons-stream", "delcons.>")
          _   <- jsm.addOrUpdateConsumer(
                 "delcons-stream",
                 ConsumerConfig.durable("del-me").copy(filterSubject = Some("delcons.>"))
               )
          _     <- jsm.deleteConsumer("delcons-stream", "del-me")
          names <- jsm.getConsumerNames("delcons-stream")
          _     <- jsm.deleteStream("delcons-stream")
        } yield assertTrue(!names.contains("del-me"))
      },

      test("getConsumerInfo returns consumer metadata") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          _   <- createStream(jsm, "consinfo-stream", "consinfo.>")
          _   <- jsm.addOrUpdateConsumer(
                 "consinfo-stream",
                 ConsumerConfig.durable("info-cons").copy(filterSubject = Some("consinfo.>"))
               )
          info <- jsm.getConsumerInfo("consinfo-stream", "info-cons")
          _    <- jsm.deleteStream("consinfo-stream")
        } yield assertTrue(info.name == "info-cons", info.streamName == "consinfo-stream")
      },

      test("getMessage retrieves a specific message by sequence number") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "getmsg-stream", "getmsg.>")
          ack <- js.publish(Subject("getmsg.1"), Chunk.fromArray("hello-seq".getBytes))
          msg <- jsm.getMessage("getmsg-stream", ack.seqno)
          _   <- jsm.deleteStream("getmsg-stream")
        } yield assertTrue(new String(msg.getData) == "hello-seq")
      },

      test("deleteMessage hard-deletes a message by sequence number") {
        for {
          jsm    <- ZIO.service[JetStreamManagement]
          js     <- ZIO.service[JetStream]
          _      <- createStream(jsm, "delmsg-stream", "delmsg.>")
          ack    <- js.publish(Subject("delmsg.1"), Chunk.fromArray("to-delete".getBytes))
          _      <- jsm.deleteMessage("delmsg-stream", ack.seqno)
          result <- jsm.getMessage("delmsg-stream", ack.seqno).either
          _      <- jsm.deleteStream("delmsg-stream")
        } yield assertTrue(result.isLeft)
      },

      test("getAccountStatistics reflects created streams") {
        for {
          jsm   <- ZIO.service[JetStreamManagement]
          _     <- createStream(jsm, "acct-stats-stream", "acctstats.>")
          stats <- jsm.getAccountStatistics
          _     <- jsm.deleteStream("acct-stats-stream")
        } yield assertTrue(stats.getStreams >= 1, stats.getConsumers >= 0)
      },

      test("StreamConfig advanced fields are accepted by the server") {
        for {
          jsm  <- ZIO.service[JetStreamManagement]
          info <- jsm.addStream(
                    StreamConfig(
                      name = "adv-stream",
                      subjects = List("adv.>"),
                      storageType = StorageType.Memory,
                      description = Some("test stream"),
                      maxBytes = 10_000_000L,
                      allowRollupHeaders = true,
                      allowDirect = true,
                      firstSequence = 1L
                    )
                  )
          _ <- jsm.deleteStream("adv-stream")
        } yield assertTrue(info.name == "adv-stream", info.subjects == List("adv.>"))
      },

      test("ConsumerConfig advanced fields are accepted by the server") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          _   <- jsm.addStream(
                 StreamConfig("cons-adv-stream", subjects = List("consadv.>"), storageType = StorageType.Memory)
               )
          info <- jsm.addOrUpdateConsumer(
                    "cons-adv-stream",
                    ConsumerConfig
                      .durable("adv-consumer")
                      .copy(
                        filterSubject = Some("consadv.>"),
                        maxDeliver = 5L,
                        maxAckPending = 100L,
                        backoff = List(1.second, 2.seconds),
                        metadata = Map("env" -> "test"),
                        sampleFrequency = Some("100%"),
                        maxPullWaiting = 10L
                      )
                  )
          _ <- jsm.deleteStream("cons-adv-stream")
        } yield assertTrue(info.name == "adv-consumer", info.streamName == "cons-adv-stream")
      }
    ),

    suite("Stream mirroring")(
      test("mirror stream receives messages published to the source") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(
                 StreamConfig("mirror-source", subjects = List("msrc.>"), storageType = StorageType.Memory)
               )
          _ <- js.publish(Subject("msrc.1"), "a")
          _ <- js.publish(Subject("msrc.2"), "b")
          _ <- js.publish(Subject("msrc.3"), "c")
          _ <- jsm.addStream(
                 StreamConfig(
                   "mirror-target",
                   storageType = StorageType.Memory,
                   mirror = Some(MirrorConfig("mirror-source"))
                 )
               )
          oc   <- js.orderedConsumer("mirror-target", OrderedConsumerConfig())
          msgs <- oc
                    .fetch[String](FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                    .runCollect
          _ <- jsm.deleteStream("mirror-target")
          _ <- jsm.deleteStream("mirror-source")
        } yield assertTrue(
          msgs.size == 3,
          msgs.map(_.value).toList == List("a", "b", "c")
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
      },

      test("publish with headers propagates headers to the consumer") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "hdrs-stream", "hdrs.>")
          _   <- jsm.addOrUpdateConsumer(
                 "hdrs-stream",
                 ConsumerConfig.durable("hdrs-cons").copy(filterSubject = Some("hdrs.>"))
               )
          params    = JsPublishParams(headers = Headers("X-Custom" -> "my-value"))
          _        <- js.publish(Subject("hdrs.test"), Chunk.fromArray("payload".getBytes), params)
          consumer <- js.consumer("hdrs-stream", "hdrs-cons")
          msg      <- consumer.next[Chunk[Byte]](5.seconds)
          _        <- msg.map(_.message.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("hdrs-stream")
        } yield assertTrue(
          msg.isDefined,
          msg.get.message.headers.get("X-Custom") == Chunk("my-value")
        )
      },

      test("publishAsync with expectedLastSeq fails when sequence doesn't match") {
        for {
          jsm     <- ZIO.service[JetStreamManagement]
          js      <- ZIO.service[JetStream]
          _       <- createStream(jsm, "expseq-stream", "expseq.>")
          _       <- js.publish(Subject("expseq.test"), "first")
          opts     = JsPublishParams(options = Some(PublishOptions(expectedLastSeqno = Some(999L))))
          ackTask <- js.publishAsync(Subject("expseq.test"), "second", opts)
          result  <- ackTask.either
          _       <- jsm.deleteStream("expseq-stream")
        } yield assertTrue(result.isLeft)
      }
    ),

    suite("Consumer pause and resume")(
      test("pause and resume a consumer") {
        for {
          jsm       <- ZIO.service[JetStreamManagement]
          _         <- jsm.addStream(StreamConfig("pause-stream", subjects = List("pause.>"), storageType = StorageType.Memory))
          _         <- jsm.addOrUpdateConsumer("pause-stream", ConsumerConfig.durable("pause-cons"))
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
          jsm  <- ZIO.service[JetStreamManagement]
          js   <- ZIO.service[JetStream]
          _    <- jsm.addStream(StreamConfig("ord-stream", subjects = List("ord.>"), storageType = StorageType.Memory))
          _    <- js.publish(Subject("ord.1"), "a")
          _    <- js.publish(Subject("ord.2"), "b")
          _    <- js.publish(Subject("ord.3"), "c")
          oc   <- js.orderedConsumer("ord-stream", OrderedConsumerConfig())
          msgs <- oc
                    .fetch[String](FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                    .runCollect
          _ <- jsm.deleteStream("ord-stream")
        } yield assertTrue(
          msgs.size == 3,
          msgs.map(_.value).toList == List("a", "b", "c")
        )
      },

      test("ordered consumer with filter subject") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- jsm.addStream(
                 StreamConfig("ord-filter-stream", subjects = List("ordf.>"), storageType = StorageType.Memory)
               )
          _   <- js.publish(Subject("ordf.x"), "x-msg")
          _   <- js.publish(Subject("ordf.y"), "y-msg")
          oc  <- js.orderedConsumer("ord-filter-stream", OrderedConsumerConfig(filterSubjects = List("ordf.x")))
          msg <- oc.next[String](5.seconds)
          _   <- jsm.deleteStream("ord-filter-stream")
        } yield assertTrue(msg.exists(_.value == "x-msg"))
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
                    .fetch[Chunk[Byte]](FetchOptions(maxMessages = 3, expiresIn = 5.seconds))
                    .tap(_.message.ack)
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
          msg      <- consumer.next[String](5.seconds)
          _        <- msg.map(_.message.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("next-stream")
        } yield assertTrue(
          msg.isDefined,
          msg.get.value == "single"
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
                    .consume[Chunk[Byte]]()
                    .tap(_.message.ack)
                    .take(2)
                    .runCollect
          _ <- jsm.deleteStream("consume-stream")
        } yield assertTrue(msgs.size == 2)
      },

      test("iterate() delivers all messages published to a stream") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "iter-stream", "iter.>")
          _   <- jsm.addOrUpdateConsumer(
                 "iter-stream",
                 ConsumerConfig.durable("iter-cons").copy(filterSubject = Some("iter.>"))
               )
          _        <- js.publish(Subject("iter.1"), Chunk.fromArray("a".getBytes))
          _        <- js.publish(Subject("iter.2"), Chunk.fromArray("b".getBytes))
          _        <- js.publish(Subject("iter.3"), Chunk.fromArray("c".getBytes))
          consumer <- js.consumer("iter-stream", "iter-cons")
          msgs     <- consumer
                    .iterate[Chunk[Byte]]()
                    .tap(_.message.ack)
                    .take(3)
                    .runCollect
          _ <- jsm.deleteStream("iter-stream")
        } yield assertTrue(msgs.size == 3)
      },

      test("iterate() with explicit ConsumeOptions delivers messages") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "iter2-stream", "iter2.>")
          _   <- jsm.addOrUpdateConsumer(
                 "iter2-stream",
                 ConsumerConfig.durable("iter2-cons").copy(filterSubject = Some("iter2.>"))
               )
          _        <- js.publish(Subject("iter2.1"), Chunk.fromArray("x".getBytes))
          _        <- js.publish(Subject("iter2.2"), Chunk.fromArray("y".getBytes))
          consumer <- js.consumer("iter2-stream", "iter2-cons")
          msgs     <- consumer
                    .iterate[Chunk[Byte]](ConsumeOptions(batchSize = 100), pollTimeout = 3.seconds)
                    .tap(_.message.ack)
                    .take(2)
                    .runCollect
          _ <- jsm.deleteStream("iter2-stream")
        } yield assertTrue(msgs.size == 2)
      }
    ),

    suite("Message acknowledgement")(
      test("nak() causes the message to be redelivered") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "nak-stream", "nak.>")
          _   <- jsm.addOrUpdateConsumer(
                 "nak-stream",
                 ConsumerConfig.durable("nak-cons").copy(filterSubject = Some("nak.>"))
               )
          _        <- js.publish(Subject("nak.1"), "payload")
          consumer <- js.consumer("nak-stream", "nak-cons")
          msg1     <- consumer.next[String](5.seconds)
          _        <- msg1.map(_.message.nak).getOrElse(ZIO.unit)
          msg2     <- consumer.next[String](5.seconds)
          _        <- msg2.map(_.message.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("nak-stream")
        } yield assertTrue(
          msg1.isDefined,
          msg2.isDefined,
          msg1.get.value == "payload",
          msg2.get.value == "payload"
        )
      },

      test("nakWithDelay() causes redelivery after the delay elapses") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "nakdelay-stream", "nakdelay.>")
          _   <- jsm.addOrUpdateConsumer(
                 "nakdelay-stream",
                 ConsumerConfig.durable("nakdelay-cons").copy(filterSubject = Some("nakdelay.>"))
               )
          _        <- js.publish(Subject("nakdelay.1"), "delayed")
          consumer <- js.consumer("nakdelay-stream", "nakdelay-cons")
          msg1     <- consumer.next[String](5.seconds)
          _        <- msg1.map(_.message.nakWithDelay(500.millis)).getOrElse(ZIO.unit)
          msg2     <- consumer.next[String](5.seconds)
          _        <- msg2.map(_.message.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("nakdelay-stream")
        } yield assertTrue(msg1.isDefined, msg2.isDefined, msg2.get.value == "delayed")
      },

      test("term() terminates a message and it is NOT redelivered") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "term-stream", "term.>")
          _   <- jsm.addOrUpdateConsumer(
                 "term-stream",
                 ConsumerConfig.durable("term-cons").copy(filterSubject = Some("term.>"))
               )
          _        <- js.publish(Subject("term.1"), "terminal")
          consumer <- js.consumer("term-stream", "term-cons")
          msg1     <- consumer.next[String](5.seconds)
          _        <- msg1.map(_.message.term).getOrElse(ZIO.unit)
          // After term(), no redelivery should occur
          msg2 <- consumer.next[String](2.seconds)
          _    <- jsm.deleteStream("term-stream")
        } yield assertTrue(msg1.isDefined, msg2.isEmpty)
      },

      test("inProgress() extends the ack deadline without triggering redelivery") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "inprog-stream", "inprog.>")
          _   <- jsm.addOrUpdateConsumer(
                 "inprog-stream",
                 ConsumerConfig.durable("inprog-cons").copy(filterSubject = Some("inprog.>"))
               )
          _        <- js.publish(Subject("inprog.1"), "working")
          consumer <- js.consumer("inprog-stream", "inprog-cons")
          msg      <- consumer.next[String](5.seconds)
          _        <- msg.map(_.message.inProgress).getOrElse(ZIO.unit)
          _        <- msg.map(_.message.ack).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("inprog-stream")
        } yield assertTrue(msg.isDefined)
      },

      test("ackSync() acknowledges and confirms the server received the ack") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "acksync-stream", "acksync.>")
          _   <- jsm.addOrUpdateConsumer(
                 "acksync-stream",
                 ConsumerConfig.durable("acksync-cons").copy(filterSubject = Some("acksync.>"))
               )
          _        <- js.publish(Subject("acksync.1"), "sync-me")
          consumer <- js.consumer("acksync-stream", "acksync-cons")
          msg      <- consumer.next[String](5.seconds)
          _        <- msg.map(_.message.ackSync(5.seconds)).getOrElse(ZIO.unit)
          _        <- jsm.deleteStream("acksync-stream")
        } yield assertTrue(msg.isDefined)
      }
    ),

    suite("Consumer unpin")(
      test("unpin on a pinned consumer returns a result without throwing") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "unpin-stream", "unpin.>")
          _   <- jsm.addOrUpdateConsumer(
                 "unpin-stream",
                 ConsumerConfig
                   .durable("unpin-cons")
                   .copy(
                     filterSubject = Some("unpin.>"),
                     priorityPolicy = Some(PriorityPolicy.PinnedClient),
                     priorityGroups = List("g1")
                   )
               )
          consumer <- js.consumer("unpin-stream", "unpin-cons")
          // No client is currently pinned, so unpin returns false (not an error)
          result <- consumer.unpin("g1").either
          _      <- jsm.deleteStream("unpin-stream")
        } yield assertTrue(result.isRight)
      }
    ),

    suite("Message access")(
      test("getLastMessage retrieves the latest message on a subject") {
        for {
          jsm <- ZIO.service[JetStreamManagement]
          js  <- ZIO.service[JetStream]
          _   <- createStream(jsm, "msg-last-stream", "msg.last.>")
          _   <- js.publish(Subject("msg.last.x"), Chunk.fromArray("first".getBytes))
          _   <- js.publish(Subject("msg.last.x"), Chunk.fromArray("second".getBytes))
          msg <- jsm.getLastMessage("msg-last-stream", "msg.last.x")
          _   <- jsm.deleteStream("msg-last-stream")
        } yield assertTrue(new String(msg.getData) == "second")
      }
    )
  ).provideShared(
    NatsTestLayers.nats,
    JetStreamManagement.live,
    JetStream.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
