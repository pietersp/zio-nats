package zio

/** Package-level conveniences for zio-nats.
  *
  * Import zio.nats._ to get all services plus these helpers.
  */
package object nats {

  /** Type alias: a ZIO effect that requires the core Nats service. */
  type NatsIO[+A] = ZIO[Nats, NatsError, A]

  /** Type alias: a ZIO effect that requires JetStream. */
  type JetStreamIO[+A] = ZIO[JetStream, NatsError, A]

  /** Implicit conversion: String -> Chunk[Byte] (UTF-8). */
  implicit class StringOps(private val s: String) extends AnyVal {
    /** Encode this string as a UTF-8 byte Chunk suitable for NATS publish. */
    def toNatsData: Chunk[Byte] =
      Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
