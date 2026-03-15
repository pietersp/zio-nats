import zio._
import zio.nats._
import zio.nats.config.NatsConfig
import zio.nats.subject.Subject

/** Realistic zio-nats example.
  *
  * Demonstrates idiomatic ZIO service + ZLayer composition:
  *   - NatsConnectionEvents wired before connect
  *   - JetStreamManagement to create a stream and durable consumer
  *   - JetStream to publish messages
  *   - Consumer.fetch to consume as a ZStream, ack each message
  *   - KeyValueManagement + KeyValue.bucket for state tracking
  *
  * Requires JetStream-enabled NATS: nats-server -js
  *
  * Run with: sbt "zioNatsExamples/run"
  */
object RealisticApp extends ZIOAppDefault {

  // ---------------------------------------------------------------------------
  // Application logic — all dependencies injected via ZIO environment
  // ---------------------------------------------------------------------------

  val program: ZIO[Nats & JetStream & JetStreamManagement & KeyValueManagement, NatsError, Unit] =
    for {
      jsm <- ZIO.service[JetStreamManagement]
      js  <- ZIO.service[JetStream]
      kvm <- ZIO.service[KeyValueManagement]

      // --- Create a JetStream stream ---
      _ <- jsm.addStream(
             StreamConfig(name = "ORDERS", subjects = List("orders.>"), storageType = StorageType.Memory)
           )

      // --- Create a durable pull consumer ---
      _ <- jsm.addOrUpdateConsumer(
             "ORDERS",
             ConsumerConfig.durable("order-processor").copy(
               filterSubject = Some("orders.>"),
               ackPolicy     = AckPolicy.Explicit
             )
           )

      // --- Create a KV bucket to track state ---
      _ <- kvm.create(KeyValueConfig(name = "app-state", storageType = StorageType.Memory))

      kv <- KeyValue.bucket("app-state")
      _  <- kv.put("processed", "0")

      // --- Publish 5 orders via JetStream ---
      _ <- ZIO.foreach(1 to 5)(i =>
             js.publish(Subject("orders.new"), s"order-$i".toNatsData)
           )
      _ <- Console.printLine("Published 5 orders").orDie

      // --- Consume the orders as a ZStream, ack each one ---
      consumer  <- js.consumer("ORDERS", "order-processor")
      _ <- consumer
             .fetch(FetchOptions(maxMessages = 5, expiresIn = 5.seconds))
             .mapZIO { msg =>
               for {
                 _     <- Console.printLine(s"Processing: ${msg.dataAsString}").orDie
                 _     <- msg.ack
                 entry <- kv.get("processed")
                 count  = entry.map(_.valueAsString.toInt).getOrElse(0)
                 _     <- kv.put("processed", (count + 1).toString)
               } yield ()
             }
             .runDrain

      // --- Report final count from KV ---
      finalEntry <- kv.get("processed")
      _ <- Console.printLine(
             s"Done. Processed: ${finalEntry.map(_.valueAsString).getOrElse("0")} orders"
           ).orDie
    } yield ()

  // ---------------------------------------------------------------------------
  // Layer wiring — compose Nats → JetStream / JetStreamManagement / KV
  // ---------------------------------------------------------------------------

  val natsLayer: ZLayer[Any, NatsError, Nats] =
    ZLayer.succeed(NatsConfig.default) >>> Nats.live

  val appLayer: ZLayer[Any, NatsError, Nats & JetStream & JetStreamManagement & KeyValueManagement] =
    natsLayer >+>
    JetStream.live >+>
    JetStreamManagement.live >+>
    KeyValueManagement.live

  // ---------------------------------------------------------------------------
  // Entry point — wire connection events before connecting
  // ---------------------------------------------------------------------------

  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      NatsConnectionEvents.make.flatMap { result =>
        val events     = result._1
        val customizer = result._2

        val logEvents = events
          .tap(e => Console.printLine(s"[nats-event] $e").orDie)
          .runDrain
          .fork

        val customConfig = NatsConfig.default.copy(optionsCustomizer = customizer)

        val layer =
          ZLayer.succeed(customConfig) >>>
          Nats.live >+>
          JetStream.live >+>
          JetStreamManagement.live >+>
          KeyValueManagement.live

        logEvents *> program.provide(layer)
      }
    }.mapError(e => new RuntimeException(e.getMessage))
}
