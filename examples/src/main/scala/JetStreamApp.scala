import zio.*
import zio.nats.*
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

case class SensorReading(sensorId: String, celsius: Double, timestamp: Long)
object SensorReading {
  given schema: Schema[SensorReading] = Schema.derived
}

/**
 * JetStream example: four consumption strategies.
 *
 * JetStream adds persistence, replay, and acknowledgement on top of NATS
 * pub/sub. This example publishes a batch of typed sensor readings and then
 * demonstrates each major way to consume them:
 *
 *   1. publishAsync — fire-and-forget batch with deferred ack collection
 *   2. fetch        — bounded pull: retrieve exactly N messages then stop
 *   3. consume      — server-push stream: messages arrive continuously;
 *                     interrupt the stream when done
 *   4. iterate      — long-running pull loop; ordered consumer guarantees
 *                     strict in-order delivery with no manual ack required
 *
 * Requires a JetStream-enabled NATS server:
 *   docker run -p 4222:4222 nats -js
 *
 * Run with: sbt "zioNatsExamples/runMain JetStreamApp"
 */
object JetStreamApp extends ZIOAppDefault {

  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  private val streamName = "SENSORS"
  private val subject    = Subject("sensors.readings")

  // ---------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------

  private def createStream(jsm: JetStreamManagement): IO[NatsError, Unit] =
    jsm.addStream(
      StreamConfig(
        name        = streamName,
        subjects    = List("sensors.>"),
        storageType = StorageType.Memory
      )
    ).unit

  // ---------------------------------------------------------------------------
  // 1. publishAsync — publish a batch, collect acks afterwards
  //
  // publishAsync enqueues the message immediately and returns a Task that
  // resolves to the server's PublishAck once the message is persisted.
  // Collecting all futures only after publishing maximises throughput by
  // pipelining multiple in-flight messages.
  // ---------------------------------------------------------------------------

  private def publishBatch(js: JetStream): IO[NatsError, Unit] = {
    val readings = List(
      SensorReading("sensor-1", 21.3, 1000),
      SensorReading("sensor-2", 23.7, 1001),
      SensorReading("sensor-1", 21.5, 1002),
      SensorReading("sensor-3", 19.8, 1003),
      SensorReading("sensor-2", 24.1, 1004),
      SensorReading("sensor-1", 21.7, 1005),
      SensorReading("sensor-3", 20.0, 1006),
      SensorReading("sensor-2", 23.9, 1007),
      SensorReading("sensor-1", 22.0, 1008),
      SensorReading("sensor-3", 20.2, 1009),
    )
    for {
      // Enqueue all messages before waiting for any ack
      futures <- ZIO.foreach(readings)(r => js.publishAsync(subject, r))
      // Now wait for all server acks
      acks    <- ZIO.foreach(futures)(_.orDie)
      _       <- Console.printLine(
                   s"Published ${acks.size} readings " +
                   s"(seq ${acks.head.seqno} – ${acks.last.seqno})"
                 ).orDie
    } yield ()
  }

  // ---------------------------------------------------------------------------
  // 2. fetch — bounded pull with explicit ack
  //
  // A durable consumer remembers its position on the server, so restarts
  // continue from where they left off. fetch() pulls a fixed batch and the
  // stream completes once the batch is fulfilled or expiresIn elapses.
  // ---------------------------------------------------------------------------

  private def demoFetch(js: JetStream, jsm: JetStreamManagement): IO[NatsError, Unit] =
    for {
      _ <- jsm.addOrUpdateConsumer(
             streamName,
             ConsumerConfig.durable("fetch-demo").copy(ackPolicy = AckPolicy.Explicit)
           )
      consumer <- js.consumer(streamName, "fetch-demo")
      _        <- Console.printLine("\n[fetch] pulling first 5 readings:").orDie
      _        <- consumer
                    .fetch[SensorReading](FetchOptions(maxMessages = 5, expiresIn = 5.seconds))
                    .mapZIO { env =>
                      Console.printLine(
                        s"  ${env.value.sensorId}: ${env.value.celsius}°C"
                      ).orDie *> env.message.ack
                    }
                    .runDrain
    } yield ()

  // ---------------------------------------------------------------------------
  // 3. consume — server-push stream
  //
  // consume() opens a long-lived server-push subscription. The server
  // delivers messages proactively up to batchSize at a time. Here we take
  // only 10 messages and then interrupt the stream; in production you would
  // run it until the fiber is cancelled.
  // ---------------------------------------------------------------------------

  private def demoConsume(js: JetStream, jsm: JetStreamManagement): IO[NatsError, Unit] =
    for {
      _ <- jsm.addOrUpdateConsumer(
             streamName,
             ConsumerConfig.durable("consume-demo").copy(
               ackPolicy    = AckPolicy.Explicit,
               deliverPolicy = DeliverPolicy.All
             )
           )
      consumer <- js.consumer(streamName, "consume-demo")
      _        <- Console.printLine("\n[consume] streaming all 10 readings via push:").orDie
      _        <- consumer
                    .consume[SensorReading]()
                    .take(10)
                    .mapZIO { env =>
                      Console.printLine(
                        s"  ${env.value.sensorId}: ${env.value.celsius}°C"
                      ).orDie *> env.message.ack
                    }
                    .runDrain
    } yield ()

  // ---------------------------------------------------------------------------
  // 4. Ordered consumer + iterate — strict in-order, no ack required
  //
  // An ordered consumer is created and managed entirely by the client; it
  // requires no server-side durable registration. The server guarantees
  // strict in-order delivery and automatically recreates the consumer on
  // reconnect or sequence gaps. No acknowledgement is needed.
  // iterate() uses a long-running pull loop; each poll waits up to
  // pollTimeout for the next message.
  // ---------------------------------------------------------------------------

  private def demoOrdered(js: JetStream): IO[NatsError, Unit] =
    for {
      _        <- Console.printLine("\n[ordered+iterate] all 10 readings in strict order:").orDie
      consumer <- js.orderedConsumer(
                    streamName,
                    OrderedConsumerConfig(deliverPolicy = Some(DeliverPolicy.All))
                  )
      _        <- consumer
                    .iterate[SensorReading](pollTimeout = 3.seconds)
                    .take(10)
                    .mapZIO { env =>
                      Console.printLine(
                        s"  ${env.value.sensorId} @ ${env.value.celsius}°C"
                      ).orDie
                    }
                    .runDrain
    } yield ()

  // ---------------------------------------------------------------------------
  // Program
  // ---------------------------------------------------------------------------

  val program: ZIO[JetStream & JetStreamManagement, NatsError, Unit] =
    for {
      js  <- ZIO.service[JetStream]
      jsm <- ZIO.service[JetStreamManagement]
      _   <- createStream(jsm)
      _   <- publishBatch(js)
      _   <- demoFetch(js, jsm)
      _   <- demoConsume(js, jsm)
      _   <- demoOrdered(js)
      _   <- Console.printLine("\nDone.").orDie
    } yield ()

  // ---------------------------------------------------------------------------
  // Layer wiring
  // ---------------------------------------------------------------------------

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(
        ZLayer.succeed(NatsConfig.default) >>> Nats.live >+>
          JetStream.live >+>
          JetStreamManagement.live
      )
      .mapError(e => new RuntimeException(e.getMessage))
}
