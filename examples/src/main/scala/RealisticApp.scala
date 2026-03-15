import zio._
import zio.nats._
import zio.nats.config.NatsConfig
import io.nats.client.api.{
  AckPolicy,
  ConsumerConfiguration,
  KeyValueConfiguration,
  StorageType,
  StreamConfiguration
}
import io.nats.client.FetchConsumeOptions

/** Realistic zio-nats example.
  *
  * Demonstrates idiomatic ZIO service + ZLayer composition:
  *   - NatsConnectionEvents wired before connect
  *   - JetStreamManagement to create a stream and durable consumer
  *   - JetStream to publish messages
  *   - JetStreamConsumer.fetch to consume as a ZStream, ack each message
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
             StreamConfiguration.builder()
               .name("ORDERS")
               .subjects("orders.>")
               .storageType(StorageType.Memory)
               .build()
           )

      // --- Create a durable pull consumer ---
      _ <- jsm.addOrUpdateConsumer(
             "ORDERS",
             ConsumerConfiguration.builder()
               .durable("order-processor")
               .filterSubject("orders.>")
               .ackPolicy(AckPolicy.Explicit)
               .build()
           )

      // --- Create a KV bucket to track state ---
      _ <- kvm.create(
             KeyValueConfiguration.builder()
               .name("app-state")
               .storageType(StorageType.Memory)
               .build()
           )
      kv <- KeyValue.bucket("app-state")
      _  <- kv.put("processed", "0")

      // --- Publish 5 orders via JetStream ---
      _ <- ZIO.foreach(1 to 5)(i =>
             js.publish(s"orders.new", s"order-$i".toNatsData)
           )
      _ <- Console.printLine("Published 5 orders").orDie

      // --- Consume the orders as a ZStream, ack each one ---
      ctx       <- js.consumerContext("ORDERS", "order-processor")
      fetchOpts  = FetchConsumeOptions.builder().maxMessages(5).expiresIn(5000).build()
      _ <- JetStreamConsumer
             .fetch(ctx, fetchOpts)
             .mapZIO { msg =>
               for {
                 _     <- Console.printLine(s"Processing: ${msg.dataAsString}").orDie
                 _     <- msg.ack
                 entry <- kv.get("processed")
                 count  = entry.map(e => new String(e.getValue).toInt).getOrElse(0)
                 _     <- kv.put("processed", (count + 1).toString)
               } yield ()
             }
             .runDrain

      // --- Report final count from KV ---
      finalEntry <- kv.get("processed")
      _ <- Console.printLine(
             s"Done. Processed: ${finalEntry.map(e => new String(e.getValue)).getOrElse("0")} orders"
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

  // ZIOAppDefault.run requires ZIO[Any, Throwable, Unit]; NatsError is not a Throwable subtype.
  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      // Connection events must be wired before the connection is opened.
      NatsConnectionEvents.make.flatMap { result =>
        val events     = result._1
        val customizer = result._2

        // Log events in the background for the lifetime of the app.
        val logEvents = events
          .tap(e => Console.printLine(s"[nats-event] $e").orDie)
          .runDrain
          .fork

        // Override the default config to attach the event listener.
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
