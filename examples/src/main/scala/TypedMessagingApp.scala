import zio.*
import zio.nats.*
import zio.nats.config.NatsConfig
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonFormat

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

case class User(name: String, age: Int)
object User {
  implicit val schema: Schema[User] = Schema.derived
}

case class Order(id: String, amount: Double, userId: String)
object Order {
  implicit val schema: Schema[Order] = Schema.derived
}

/**
 * Type-safe serialization example using the NatsCodec typeclass.
 *
 * Derives codecs from JsonFormat via the Builder pattern; no manual
 * encoding/decoding required.
 *
 * Requires a running NATS server: nats-server
 *
 * Run with: sbt "zioNatsExamples/runMain TypedMessagingApp"
 */
object TypedMessagingApp extends ZIOAppDefault {

  // Install a default NatsCodec for all Schema-annotated types using JSON.
  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      // subscribe[User] is the typed overload — auto-decoded via NatsCodec[User]
      userFiber <- Nats
                     .subscribe[User](Subject("demo.users"))
                     .take(3)
                     .tap(user => Console.printLine(s"  user:  ${user.name}, age ${user.age}").orDie)
                     .runDrain
                     .fork

      orderFiber <- Nats
                      .subscribe[Order](Subject("demo.orders"))
                      .take(2)
                      .tap(order => Console.printLine(s"  order: ${order.id} - $$${order.amount}").orDie)
                      .runDrain
                      .fork

      _ <- ZIO.sleep(200.millis)

      _ <- Console.printLine("Publishing users...").orDie
      _ <- Nats.publish(Subject("demo.users"), User("Alice", 30))
      _ <- Nats.publish(Subject("demo.users"), User("Bob", 25))
      _ <- Nats.publish(Subject("demo.users"), User("Charlie", 35))

      _ <- Console.printLine("Publishing orders...").orDie
      _ <- Nats.publish(Subject("demo.orders"), Order("ord-1", 99.99, "Alice"))
      _ <- Nats.publish(Subject("demo.orders"), Order("ord-2", 14.50, "Bob"))

      _ <- userFiber.join
      _ <- orderFiber.join
      _ <- Console.printLine("Done.").orDie
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
