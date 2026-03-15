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

  /** Retry schedule: up to 20 attempts with exponential backoff, max 10 seconds total. */
  private val retrySchedule: Schedule[Any, Any, Any] =
    Schedule.exponential(100.millis).zipRight(Schedule.upTo(10.seconds))

  /** Try to connect to NATS, retrying with exponential backoff until successful or timeout. */
  private def testConnection(config: NatsConfig): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val conn = io.nats.client.Nats.connect(config.toOptions)
      conn.close()
    }

  /** Full Nats service layer backed by a testcontainer with connection verification.
    *
    * Composes: container -> config -> wait for connection -> Nats.live
    */
  val nats: ZLayer[Any, Throwable, Nats] =
    container >>> config >>> ZLayer.fromFunction { (cfg: NatsConfig) =>
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(testConnection(cfg).retry(retrySchedule))
          .getOrThrow()
        cfg
      }
    } >>> Nats.live.mapError(e => new RuntimeException(e.message, e))
}
