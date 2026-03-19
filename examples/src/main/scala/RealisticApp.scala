import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

/** A purchase order published to JetStream. */
case class PurchaseOrder(id: String, item: String, quantity: Int, totalCents: Long)
object PurchaseOrder {
  given schema: Schema[PurchaseOrder] = Schema.derived
}

/**
 * Realistic zio-nats example.
 *
 * Demonstrates idiomatic ZIO service + ZLayer composition:
 *   - Typed serialization via NatsCodec + JsonFormat (eager codec construction)
 *   - Nats.lifecycleEvents to observe connection lifecycle
 *   - JetStreamManagement to create a stream and durable consumer
 *   - JetStream to publish typed Order messages
 *   - Consumer.fetch to consume as a ZStream, ack each message
 *   - KeyValueManagement + KeyValue.bucket for state tracking
 *
 * Requires JetStream-enabled NATS: nats-server -js
 *
 * Run with: sbt "zioNatsExamples/run"
 */
object RealisticApp extends ZIOAppDefault {

  // Install JSON codecs for all Schema-annotated types.
  // NatsCodec[Order] is derived eagerly here: if the Schema is missing or the
  // format is incompatible the application fails immediately at startup, not
  // silently inside the first js.publish call.
  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

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
             ConsumerConfig
               .durable("order-processor")
               .copy(
                 filterSubject = Some("orders.>"),
                 ackPolicy = AckPolicy.Explicit
               )
           )

      // --- Create a KV bucket to track processed count ---
      _ <- kvm.create(KeyValueConfig(name = "app-state", storageType = StorageType.Memory))

      kv <- KeyValue.bucket("app-state")
      _  <- kv.put("processed", "0")

      // --- Publish 5 typed orders via JetStream ---
      // Encoding uses the pre-built NatsCodec[Order]; failures surface as
      // NatsError.SerializationError rather than untyped JVM exceptions.
      orders = List(
                 PurchaseOrder("ord-1", "Widget", 2, 1998),
                 PurchaseOrder("ord-2", "Gadget", 1, 4999),
                 PurchaseOrder("ord-3", "Doohickey", 5, 2495),
                 PurchaseOrder("ord-4", "Thingamajig", 1, 899),
                 PurchaseOrder("ord-5", "Gizmo", 3, 3597)
               )
      _ <- ZIO.foreachDiscard(orders)(order => js.publish(Subject("orders.new"), order))
      _ <- Console.printLine("Published 5 orders").orDie

      // --- Consume the orders as a ZStream of JsEnvelope[PurchaseOrder] ---
      // Each env.value is a decoded PurchaseOrder; env.message carries the ack handle.
      consumer <- js.consumer("ORDERS", "order-processor")
      _        <- consumer
             .fetch[PurchaseOrder](FetchOptions(maxMessages = 5, expiresIn = 5.seconds))
             .mapZIO { env =>
               for {
                 _     <- Console.printLine(s"Processing: ${env.value.id} — ${env.value.item} x${env.value.quantity}").orDie
                 _     <- env.message.ack
                 entry <- kv.get[String]("processed")
                 count  = entry.map(_.value.toInt).getOrElse(0)
                 _     <- kv.put("processed", (count + 1).toString)
               } yield ()
             }
             .runDrain

      // --- Report final count from KV ---
      finalEntry <- kv.get[String]("processed")
      _          <- Console
             .printLine(s"Done. Processed: ${finalEntry.map(_.value).getOrElse("0")} orders")
             .orDie
    } yield ()

  // ---------------------------------------------------------------------------
  // Layer wiring — compose Nats → JetStream / JetStreamManagement / KV
  // ---------------------------------------------------------------------------

  val appLayer: ZLayer[Any, NatsError, Nats & JetStream & JetStreamManagement & KeyValueManagement] =
    ZLayer.succeed(NatsConfig.default) >>> Nats.live >+>
      JetStream.live >+>
      JetStreamManagement.live >+>
      KeyValueManagement.live

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  val run: ZIO[Any, Throwable, Unit] =
    (for {
      nats  <- ZIO.service[Nats]
      fiber <- nats.lifecycleEvents
                 .tap(e => Console.printLine(s"[nats-event] $e").orDie)
                 .takeUntil(_ == NatsEvent.Closed)
                 .runDrain
                 .fork
      _ <- program
      _ <- fiber.interrupt
    } yield ()).provide(appLayer).mapError(e => new RuntimeException(e.getMessage))
}
