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
  given schema: Schema[User] = Schema.derived
}

case class Order(id: String, amount: Double, userId: String)
object Order {
  given schema: Schema[Order] = Schema.derived
}

/**
 * Type-safe serialization example using the NatsCodec typeclass.
 *
 * `NatsCodec.fromFormat(JsonFormat)` creates a `Builder` that can derive
 * `NatsCodec[A]` for any type `A` that has a `Schema[A]` in scope.
 *
 * The `import jsonCodecs.derived` line brings the codec derivation into scope.
 * Codecs are built lazily on first use per type and then cached in the Builder:
 *   - The first call to publish/subscribe for a given type builds the codec.
 *   - If a type has no `Schema` or the format cannot handle it, an exception
 *     is thrown at that point, not buried inside a later encode call.
 *   - Subsequent calls for the same type reuse the cached codec.
 *
 * Requires a running NATS server: nats-server
 *
 * Run with: sbt "zioNatsExamples/runMain TypedMessagingApp"
 */
object TypedMessagingApp extends ZIOAppDefault {

  // Build the codec from JsonFormat. Codec derivation for each type happens
  // at runtime on first use (the implicit selection is at compile time, but
  // the codec object is built lazily and then cached in the Builder). If the
  // format cannot derive a codec (e.g. missing Schema), it throws on first use
  // rather than silently failing inside an encode call.
  private val jsonCodecs = NatsCodec.fromFormat(JsonFormat)
  import jsonCodecs.derived

  val program: ZIO[Nats, NatsError, Unit] =
    for {
      nats <- ZIO.service[Nats]

      // subscribe[User] decodes each message automatically via NatsCodec[User].
      // Decode failures surface as NatsError.DecodingError in the stream.
      userFiber <- nats
                     .subscribe[User](Subject("demo.users"))
                     .take(3)
                     .tap(env => Console.printLine(s"  user:  ${env.value.name}, age ${env.value.age}").orDie)
                     .runDrain
                     .fork

      orderFiber <- nats
                      .subscribe[Order](Subject("demo.orders"))
                      .take(2)
                      .tap(env => Console.printLine(s"  order: ${env.value.id} - $$${env.value.amount}").orDie)
                      .runDrain
                      .fork

      _ <- ZIO.sleep(200.millis)

      // Publish typed values. NatsCodec[User] and NatsCodec[Order] were built
      // eagerly above; encoding here just calls the pre-built codec. Any
      // encoding failure (e.g. OOM) surfaces as NatsError.SerializationError.
      _ <- Console.printLine("Publishing users...").orDie
      _ <- nats.publish(Subject("demo.users"), User("Alice", 30))
      _ <- nats.publish(Subject("demo.users"), User("Bob", 25))
      _ <- nats.publish(Subject("demo.users"), User("Charlie", 35))

      _ <- Console.printLine("Publishing orders...").orDie
      _ <- nats.publish(Subject("demo.orders"), Order("ord-1", 99.99, "Alice"))
      _ <- nats.publish(Subject("demo.orders"), Order("ord-2", 14.50, "Bob"))

      _ <- userFiber.join
      _ <- orderFiber.join
      _ <- Console.printLine("Done.").orDie
    } yield ()

  val run: ZIO[Any, Throwable, Unit] =
    program
      .provide(ZLayer.succeed(NatsConfig.default), Nats.live)
      .mapError(e => new RuntimeException(e.getMessage))
}
