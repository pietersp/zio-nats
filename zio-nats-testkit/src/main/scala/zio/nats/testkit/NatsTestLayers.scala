package zio.nats.testkit

import zio._
import zio.nats._
import zio.nats.config.NatsConfig

/** Pre-built ZLayers for integration testing against a real NATS server.
  *
  * Usage:
  * {{{
  *   .provideShared(NatsTestLayers.nats)
  * }}}
  *
  * The NATS container starts once per suite (provideShared) and is stopped
  * automatically after all tests finish.
  */
object NatsTestLayers {

  /** ZLayer that starts a NATS Docker container and provides it as a service. */
  val container: ZLayer[Any, Throwable, NatsContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = NatsContainer()
          c.start()
          c
        }
      )(c => ZIO.attemptBlocking(c.stop()).ignoreLogged)
    }

  /** NatsConfig derived from a running container (reads the mapped port). */
  val config: ZLayer[NatsContainer, Nothing, NatsConfig] =
    ZLayer.fromFunction((c: NatsContainer) =>
      NatsConfig(servers = List(c.clientUrl))
    )

  /** Try to connect to NATS, retrying with exponential backoff until successful or timeout. */
  private def awaitNatsConnection(config: NatsConfig, timeout: Duration): ZIO[Any, Throwable, Unit] = {
    val maxAttempts = 20
    val baseDelay = 100.millis

    def attempt(n: Int): ZIO[Any, Throwable, Unit] = {
      if (n >= maxAttempts) {
        ZIO.fail(new RuntimeException(s"NATS server not reachable after ${timeout} (${maxAttempts} attempts)"))
      } else {
        ZIO.attemptBlocking {
          val conn = io.nats.client.Nats.connect(config.toOptions)
          conn.close()
        }.catchAll { _ =>
          if (n < maxAttempts - 1) {
            ZIO.sleep(baseDelay * (n + 1)).flatMap(_ => attempt(n + 1))
          } else {
            ZIO.fail(new RuntimeException(s"NATS server not reachable after ${timeout}"))
          }
        }
      }
    }

    attempt(0)
  }

  /** Full Nats service layer backed by a testcontainer with connection verification.
    *
    * Composes: container -> config -> wait for connection -> Nats.live
    */
  val nats: ZLayer[Any, Throwable, Nats] =
    container >>> config >>> ZLayer.fromFunction { (cfg: NatsConfig) =>
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(awaitNatsConnection(cfg, 10.seconds))
          .getOrThrow()
        cfg
      }
    } >>> Nats.live.mapError(e => new RuntimeException(e.message, e))
}
