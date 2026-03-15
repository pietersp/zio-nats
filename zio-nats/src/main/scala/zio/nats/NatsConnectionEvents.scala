package zio.nats

import io.nats.client.{Connection => JConnection, ConnectionListener, ErrorListener}
import io.nats.client.Options
import zio._
import zio.stream._

/** ADT for NATS connection lifecycle events. */
sealed trait NatsEvent
object NatsEvent {
  final case class Connected(url: String)                   extends NatsEvent
  final case class Disconnected(url: String)               extends NatsEvent
  final case class Reconnected(url: String)                extends NatsEvent
  final case class ServersDiscovered(url: String)          extends NatsEvent
  case object Closed                                        extends NatsEvent
  case object LameDuckMode                                  extends NatsEvent
  final case class Error(message: String)                  extends NatsEvent
  final case class ExceptionOccurred(ex: Throwable)        extends NatsEvent
}

/** Provides a ZStream of connection lifecycle events via a Hub.
  *
  * Usage:
  * {{{
  *   for {
  *     (events, customizer) <- NatsConnectionEvents.make
  *     nats <- Nats.make(NatsConfig(optionsCustomizer = customizer))
  *     _    <- events.take(1).runDrain.fork
  *   } yield nats
  * }}}
  *
  * The returned customizer must be applied to NatsConfig.optionsCustomizer
  * before the connection is established.
  */
object NatsConnectionEvents {

  /** Create an event Hub and an Options.Builder customizer that wires it up.
    *
    * Returns:
    *   - A ZStream that emits events as they occur (unbounded, backed by a Hub)
    *   - An Options.Builder => Options.Builder function to attach to NatsConfig
    *
    * The Hub (and therefore the stream) lives for the duration of the enclosing Scope.
    */
  def make: ZIO[Scope, Nothing, (ZStream[Any, Nothing, NatsEvent], Options.Builder => Options.Builder)] =
    for {
      hub    <- Hub.unbounded[NatsEvent]
      stream  = ZStream.fromHub(hub)
      customizer = (builder: Options.Builder) => {
        builder
          .connectionListener(new ConnectionListener {
            override def connectionEvent(
              conn: JConnection,
              eventType: ConnectionListener.Events
            ): Unit = {
              val url   = Option(conn.getConnectedUrl).getOrElse("unknown")
              val event = eventType match {
                case ConnectionListener.Events.CONNECTED           => NatsEvent.Connected(url)
                case ConnectionListener.Events.DISCONNECTED        => NatsEvent.Disconnected(url)
                case ConnectionListener.Events.RECONNECTED         => NatsEvent.Reconnected(url)
                case ConnectionListener.Events.CLOSED              => NatsEvent.Closed
                case ConnectionListener.Events.LAME_DUCK           => NatsEvent.LameDuckMode
                case ConnectionListener.Events.RESUBSCRIBED        => NatsEvent.Reconnected(url)
                case ConnectionListener.Events.DISCOVERED_SERVERS  => NatsEvent.ServersDiscovered(url)
              }
              zio.Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(hub.publish(event))
                  .getOrThrowFiberFailure()
              }
            }
          })
          .errorListener(new ErrorListener {
            override def errorOccurred(conn: JConnection, error: String): Unit =
              zio.Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(hub.publish(NatsEvent.Error(error)))
                  .getOrThrowFiberFailure()
              }
            override def exceptionOccurred(conn: JConnection, exp: Exception): Unit =
              zio.Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(hub.publish(NatsEvent.ExceptionOccurred(exp)))
                  .getOrThrowFiberFailure()
              }
          })
      }
    } yield (stream, customizer)
}
