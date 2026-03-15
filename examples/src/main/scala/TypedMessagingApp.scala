import zio._
import zio.nats._
import zio.nats.config.NatsConfig
import zio.blocks.schema.Schema

// ---------------------------------------------------------------------------
// Domain model — derive a Schema for automatic JSON serialization
// ---------------------------------------------------------------------------

case class User(name: String, age: Int)
object User {
  implicit val schema: Schema[User] = Schema.derived
}

case class Order(id: String, amount: Double, userId: String)
object Order {
  implicit val schema: Schema[Order] = Schema.derived
}

/** Type-safe serialization example.
  *
  * Demonstrates publish and subscribe with automatic JSON serialization using
  * zio-blocks Schema. No manual encoding/decoding — just pass your case class.
  *
  * Requires a running NATS server: nats-server
  *
  * Run with: sbt "zioNatsExamples/runMain TypedMessagingApp"
  */
object TypedMessagingApp extends ZIOAppDefault {

  val program: ZIO[Nats & NatsConfig, NatsError, Unit] =
    for {
      // Subscribe and automatically deserialize incoming JSON to User
      userFiber <- Nats.subscribeAs[User](Subject("demo.users"))
                     .take(3)
                     .tap(user => Console.printLine(s"  user:  ${user.name}, age ${user.age}").orDie)
                     .runDrain
                     .fork

      // Subscribe and automatically deserialize to Order
      orderFiber <- Nats.subscribeAs[Order](Subject("demo.orders"))
                      .take(2)
                      .tap(order => Console.printLine(s"  order: ${order.id} — $$${order.amount} (user: ${order.userId})").orDie)
                      .runDrain
                      .fork

      _ <- ZIO.sleep(200.millis)

      // Publish typed values — serialized to JSON automatically
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
      .provide(
        ZLayer.succeed(NatsConfig.default),
        Nats.live
      )
      .mapError(e => new RuntimeException(e.getMessage))
}
