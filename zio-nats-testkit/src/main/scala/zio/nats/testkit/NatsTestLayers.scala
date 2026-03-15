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

  /** ZLayer that starts a NATS Docker container and provides it as a service.
    *
    * A short sleep after start() ensures the port binding is ready on Podman/WSL
    * before we attempt to connect (Podman has slight latency vs Docker for port publishing).
    */
  val container: ZLayer[Any, Throwable, NatsContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = NatsContainer()
          c.start()
          Thread.sleep(500) // allow port binding to settle on Podman/WSL
          c
        }
      )(c => ZIO.attemptBlocking(c.stop()).ignoreLogged)
    }

  /** NatsConfig derived from a running container (reads the mapped port). */
  val config: ZLayer[NatsContainer, Nothing, NatsConfig] =
    ZLayer.fromFunction((c: NatsContainer) =>
      NatsConfig(servers = List(c.clientUrl))
    )

  /** Full Nats service layer backed by a testcontainer.
    *
    * Composes: container -> config -> Nats.live
    */
  val nats: ZLayer[Any, Throwable, Nats] =
    container >>> config >>> Nats.live.mapError(e => new RuntimeException(e.message, e))
}
