package zio.nats

import zio._

/**
 * Helpers for implementing NATS RPC services (request-response via reply
 * subjects).
 *
 * ==Example==
 *
 * {{{
 * case class EchoRequest(message: String)
 * case class EchoResponse(message: String)
 *
 * // Assuming Schema and NatsCodec instances are in scope:
 * NatsRpc
 *   .respond[EchoRequest, EchoResponse](Subject("echo")) { req =>
 *     ZIO.succeed(EchoResponse(req.message))
 *   }
 *   .provideSomeLayer[Nats](Scope.default)
 * }}}
 */
object NatsRpc {

  /**
   * Subscribe to `subject` and respond to each incoming request.
   *
   * For each message:
   *   1. Decode the payload as `A` using [[NatsCodec]][A].
   *   2. Invoke `handler` with the decoded value.
   *   3. Encode the result as `B` and publish it to the message's `replyTo`.
   *
   * Messages without a `replyTo` subject are silently ignored.
   * Per-message failures (decode errors, handler errors, encode errors) are
   * logged via ZIO's default fiber-failure logger and skipped — the responder
   * fiber continues processing subsequent messages. Only infrastructure-level
   * stream errors (e.g. connection loss) terminate the responder fiber.
   *
   * The subscription runs in a forked fiber linked to the enclosing [[Scope]];
   * it is interrupted automatically when the scope closes.
   *
   * @param subject
   *   The subject to listen on for incoming requests.
   * @param handler
   *   A function from decoded request `A` to effect producing `B`.
   * @tparam A
   *   The request type — requires [[NatsCodec]][A] in given scope.
   * @tparam B
   *   The response type — requires [[NatsCodec]][B] in given scope.
   * @return
   *   A scoped effect that manages the subscription lifecycle.
   */
  def respond[A: NatsCodec, B: NatsCodec](
    subject: Subject
  )(
    handler: A => IO[NatsError, B]
  ): ZIO[Nats & Scope, NatsError, Unit] =
    for {
      nats <- ZIO.service[Nats]
      _    <- nats
                .subscribe[Chunk[Byte]](subject)
                .mapZIO { rawEnv =>
                  (for {
                    decoded  <- ZIO.fromEither(rawEnv.message.decode[A])
                                  .mapError(e => NatsError.DecodingError(e.message, e))
                    response <- handler(decoded)
                    _        <- rawEnv.message.replyTo match {
                                  case Some(replyTo) =>
                                    ZIO
                                      .attempt(NatsCodec[B].encode(response))
                                      .mapError(e => NatsError.SerializationError(s"Failed to encode RPC response: ${e.toString}", e))
                                      .flatMap(bytes => nats.publish(replyTo, bytes, PublishParams.empty))
                                  case None =>
                                    ZIO.unit
                                }
                  } yield ()).ignoreLogged
                }
                .runDrain
                .forkScoped
    } yield ()
}
