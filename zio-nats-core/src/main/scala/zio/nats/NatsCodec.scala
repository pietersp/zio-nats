package zio.nats

import zio.Chunk

import java.nio.charset.StandardCharsets

/**
 * Typeclass for encoding and decoding NATS message payloads.
 *
 * Instances are resolved at compile time via Scala's `given`/`using` mechanism.
 * Built-in instances handle `Chunk[Byte]` (identity) and `String` (UTF-8).
 *
 * For domain types with zio-blocks schemas, add `zio-nats-zio-blocks` to your
 * dependencies and use `NatsCodec.fromFormat(JsonFormat)`.
 *
 * ==Custom codec (no framework required)==
 *
 * {{{
 * given myCodec: NatsCodec[MyType] = new NatsCodec[MyType] {
 *   def encode(value: MyType): Chunk[Byte] = ???
 *   def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, MyType] = ???
 * }
 * }}}
 *
 * @tparam A
 *   The type to encode/decode.
 */
trait NatsCodec[A] {

  /** Encode a value to bytes. Implementations should not throw. */
  def encode(value: A): Chunk[Byte]

  /**
   * Decode bytes to a value.
   *
   * @return
   *   Right(value) on success, Left([[NatsDecodeError]]) on failure.
   */
  def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, A]
}

object NatsCodec {

  /** Summon a [[NatsCodec]] instance for type A. */
  def apply[A](using codec: NatsCodec[A]): NatsCodec[A] = codec

  // ---------------------------------------------------------------------------
  // Built-in primitive codecs
  // ---------------------------------------------------------------------------

  /** Identity codec for raw byte payloads. */
  given bytesCodec: NatsCodec[Chunk[Byte]] = new NatsCodec[Chunk[Byte]] {
    def encode(value: Chunk[Byte]): Chunk[Byte]                          = value
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, Chunk[Byte]] = Right(bytes)
  }

  /** UTF-8 string codec. */
  given stringCodec: NatsCodec[String] = new NatsCodec[String] {
    def encode(value: String): Chunk[Byte] =
      Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, String] =
      Right(new String(bytes.toArray, StandardCharsets.UTF_8))
  }
}

// ---------------------------------------------------------------------------
// ErrorCodecPart — internal per-member codec for union error types
// ---------------------------------------------------------------------------

/**
 * Internal typeclass representing the codec for a single concrete error type.
 *
 * Intentionally has no instance for `Nothing` (requires `NatsCodec[E]`, which
 * has no `Nothing` instance). This absence is load-bearing: it prevents the
 * [[TypedErrorCodec.forUnion]] combinator from resolving `TypedErrorCodec[E]`
 * for a concrete `E` via the degenerate path `forUnion[E, Nothing]` (which
 * would require `ErrorCodecPart[Nothing]` and fails cleanly).
 *
 * Users never interact with this typeclass directly.
 */
private[nats] sealed trait ErrorCodecPart[E]:
  private[nats] def tag: String

  /**
   * The JVM runtime class for `E`.
   *
   * Used by [[TypedErrorCodec.union2]] and [[TypedErrorCodec.union3]] to
   * dispatch encoding at runtime via [[Class#isInstance]], since generic type
   * parameters are erased on the JVM and pattern matching on them (`case a: A
   * @unchecked`)
   *   always succeeds regardless of the actual type.
   */
  private[nats] def runtimeClass: Class[?]
  private[nats] def encode(e: E): Chunk[Byte]
  private[nats] def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, E]

private[nats] object ErrorCodecPart:
  /**
   * Derive an [[ErrorCodecPart]] from `NatsCodec[E]` and `ClassTag[E]`.
   *
   * The type tag is the JVM FQDN of `E` (e.g. `"com.example.ValidationError"`).
   * `ClassTag` is in implicit scope automatically for all concrete types.
   */
  given [E](using c: NatsCodec[E], ct: scala.reflect.ClassTag[E]): ErrorCodecPart[E] with
    val tag: String                                            = ct.runtimeClass.getName
    val runtimeClass: Class[?]                                 = ct.runtimeClass
    def encode(e: E): Chunk[Byte]                              = c.encode(e)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, E] = c.decode(bytes)

// ---------------------------------------------------------------------------
// TypedErrorCodec — internal bridge for service framework error encoding
// ---------------------------------------------------------------------------

/**
 * Internal typeclass for encoding and decoding service handler error types,
 * including union types discriminated by a FQDN type tag.
 *
 * Used by [[zio.nats.service.ServiceEndpoint]] to encode domain errors into the
 * reply body on the server side, and by [[zio.nats.Nats.requestService]] to
 * decode them on the client side.
 *
 * The server sets a `Nats-Service-Error-Type` header to the FQDN of the
 * concrete error class. The client reads this header and dispatches to the
 * correct codec via [[tagRoutes]].
 *
 * Three instances are derived automatically — users never interact with this
 * typeclass directly:
 *   - `Nothing` — trivial infallible instance
 *   - Single `E: ErrorCodecPart` (i.e. `E: NatsCodec: ClassTag`) — tags by
 *     class FQDN; resolved from [[LowPriorityTypedErrorCodecs]]
 *   - Union `A | B` — resolved from [[TypedErrorCodec.forUnion]]; the right
 *     member uses [[ErrorCodecPart]] (not `TypedErrorCodec`) to prevent
 *     implicit divergence via the degenerate `forUnion[E, Nothing]` path
 */
private[nats] sealed trait TypedErrorCodec[E]:
  /**
   * Encode the error value and produce the type discriminator tag (FQDN of the
   * concrete runtime class).
   */
  def encode(e: E): (Chunk[Byte], String)

  /**
   * Decode bytes using the type tag from the `Nats-Service-Error-Type` header.
   * For single-type codecs the tag is verified; for union codecs it routes to
   * the correct member codec.
   */
  def decode(bytes: Chunk[Byte], typeTag: String): Either[NatsDecodeError, E]

  /**
   * O(1) routing table from type tag to member decoder, widened to `E`. Built
   * once at construction; the union combinator merges member tables.
   */
  private[nats] def tagRoutes: Map[String, Chunk[Byte] => Either[NatsDecodeError, E]]

private[nats] object TypedErrorCodec:

  /**
   * Safe instance for infallible endpoints (`Err = Nothing`).
   *
   * Both methods are statically unreachable: a handler typed `IO[Nothing, Out]`
   * can never produce a `Nothing` value.
   */
  given TypedErrorCodec[Nothing] with
    def encode(e: Nothing): (Chunk[Byte], String)                                     = e // unreachable
    def decode(bytes: Chunk[Byte], typeTag: String): Either[NatsDecodeError, Nothing] =
      Left(NatsDecodeError("Unexpected typed error payload for infallible endpoint"))
    private[nats] val tagRoutes: Map[String, Chunk[Byte] => Either[NatsDecodeError, Nothing]] =
      Map.empty

  /**
   * Derive an instance from [[ErrorCodecPart]] for a single concrete `E`.
   *
   * `ClassTag` is in implicit scope automatically for all concrete types. There
   * is intentionally no instance for union types — union codecs are built
   * explicitly via [[union2]] or [[union3]] and captured in the
   * [[zio.nats.service.ServiceEndpoint]] descriptor at the call site of
   * `failsWith`.
   *
   * Note: Scala 3 does not reliably decompose union types during implicit
   * search (i.e., a `given [A, B]: TypedErrorCodec[A | B]` would not be found
   * for `TypedErrorCodec[X | Y]`). The explicit `union2`/`union3` factory
   * methods on [[zio.nats.service.ServiceEndpoint]] are therefore the supported
   * API for union error types.
   */
  given fromPart[E](using p: ErrorCodecPart[E]): TypedErrorCodec[E] with
    private[nats] val tagRoutes: Map[String, Chunk[Byte] => Either[NatsDecodeError, E]] =
      Map(p.tag -> (b => p.decode(b)))
    def encode(e: E): (Chunk[Byte], String) =
      (p.encode(e), p.tag)
    def decode(bytes: Chunk[Byte], typeTag: String): Either[NatsDecodeError, E] =
      tagRoutes
        .get(typeTag)
        .orElse(if typeTag.isEmpty then Some(b => p.decode(b)) else None)
        .map(_(bytes))
        .getOrElse(Left(NatsDecodeError(s"Unknown error type discriminator: '$typeTag'")))

  /**
   * Explicit factory for a 2-member union codec.
   *
   * Called internally by [[zio.nats.service.ServiceEndpoint.failsWith]] when
   * two type parameters are supplied. `tagRoutes` is built once at construction
   * and dispatch is O(1).
   */
  private[nats] def union2[A, B](pa: ErrorCodecPart[A], pb: ErrorCodecPart[B]): TypedErrorCodec[A | B] =
    new TypedErrorCodec[A | B]:
      private[nats] val tagRoutes: Map[String, Chunk[Byte] => Either[NatsDecodeError, A | B]] =
        Map(
          pa.tag -> (b => pa.decode(b).map(a => a: A | B)),
          pb.tag -> (b => pb.decode(b).map(v => v: A | B))
        )
      // Use isInstance for runtime dispatch: generic type params are erased on
      // the JVM so `case a: A @unchecked` would always match the first case.
      def encode(e: A | B): (Chunk[Byte], String) =
        if pa.runtimeClass.isInstance(e) then (pa.encode(e.asInstanceOf[A]), pa.tag)
        else (pb.encode(e.asInstanceOf[B]), pb.tag)
      def decode(bytes: Chunk[Byte], typeTag: String): Either[NatsDecodeError, A | B] =
        tagRoutes
          .get(typeTag)
          .map(_(bytes))
          .getOrElse(Left(NatsDecodeError(s"Unknown error type discriminator: '$typeTag'")))

  /**
   * Explicit factory for a 3-member union codec.
   *
   * Called internally by [[zio.nats.service.ServiceEndpoint.failsWith]] when
   * three type parameters are supplied.
   */
  private[nats] def union3[A, B, C](
    pa: ErrorCodecPart[A],
    pb: ErrorCodecPart[B],
    pc: ErrorCodecPart[C]
  ): TypedErrorCodec[A | B | C] =
    new TypedErrorCodec[A | B | C]:
      private[nats] val tagRoutes: Map[String, Chunk[Byte] => Either[NatsDecodeError, A | B | C]] =
        Map(
          pa.tag -> (b => pa.decode(b).map(a => a: A | B | C)),
          pb.tag -> (b => pb.decode(b).map(v => v: A | B | C)),
          pc.tag -> (b => pc.decode(b).map(v => v: A | B | C))
        )
      // Use isInstance for runtime dispatch: generic type params are erased on
      // the JVM so `case a: A @unchecked` would always match the first case.
      def encode(e: A | B | C): (Chunk[Byte], String) =
        if pa.runtimeClass.isInstance(e) then (pa.encode(e.asInstanceOf[A]), pa.tag)
        else if pb.runtimeClass.isInstance(e) then (pb.encode(e.asInstanceOf[B]), pb.tag)
        else (pc.encode(e.asInstanceOf[C]), pc.tag)
      def decode(bytes: Chunk[Byte], typeTag: String): Either[NatsDecodeError, A | B | C] =
        tagRoutes
          .get(typeTag)
          .map(_(bytes))
          .getOrElse(Left(NatsDecodeError(s"Unknown error type discriminator: '$typeTag'")))
