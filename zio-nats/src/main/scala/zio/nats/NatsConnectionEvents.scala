package zio.nats

import io.nats.client.{ConnectionListener, ErrorListener, Options, Connection as JConnection}
import zio.*
import zio.stream.*

import java.util.concurrent.LinkedBlockingQueue

/** ADT for NATS connection lifecycle events. */
sealed trait NatsEvent
object NatsEvent {
  final case class Connected(url: String)           extends NatsEvent
  final case class Disconnected(url: String)        extends NatsEvent
  final case class Reconnected(url: String)         extends NatsEvent
  final case class ServersDiscovered(url: String)   extends NatsEvent
  case object Closed                                extends NatsEvent
  case object LameDuckMode                          extends NatsEvent
  final case class Error(message: String)           extends NatsEvent
  final case class ExceptionOccurred(ex: Throwable) extends NatsEvent
}

/**
 * Provides a ZStream of connection lifecycle events via a Hub.
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

  /**
   * Create an event Hub and an Options.Builder customizer that wires it up.
   *
   * Returns:
   *   - A ZStream that emits events as they occur (unbounded, backed by a Hub)
   *   - An Options.Builder => Options.Builder function to attach to NatsConfig
   *
   * The Hub (and therefore the stream) lives for the duration of the enclosing
   * Scope.
   */
  def make: ZIO[Scope, Nothing, (ZStream[Any, Nothing, NatsEvent], Options.Builder => Options.Builder)] =
    for {
      jQueue <- ZIO.succeed(new LinkedBlockingQueue[NatsEvent]())
      hub    <- Hub.unbounded[NatsEvent]
      _      <- ZIO.acquireRelease(
             ZStream
               .repeatZIO(ZIO.attemptBlocking(jQueue.take()).orDie)
               .runForeach(hub.publish)
               .fork
           )(_.interrupt)
      stream     = ZStream.fromHub(hub)
      customizer = (builder: Options.Builder) => {
                     def offer(event: NatsEvent): Unit = jQueue.put(event)
                     builder.connectionListener { (conn: JConnection, eventType: ConnectionListener.Events) =>
                       val url   = Option(conn.getConnectedUrl).getOrElse("unknown")
                       val event = eventType match {
                         case ConnectionListener.Events.CONNECTED          => NatsEvent.Connected(url)
                         case ConnectionListener.Events.DISCONNECTED       => NatsEvent.Disconnected(url)
                         case ConnectionListener.Events.RECONNECTED        => NatsEvent.Reconnected(url)
                         case ConnectionListener.Events.CLOSED             => NatsEvent.Closed
                         case ConnectionListener.Events.LAME_DUCK          => NatsEvent.LameDuckMode
                         case ConnectionListener.Events.RESUBSCRIBED       => NatsEvent.Reconnected(url)
                         case ConnectionListener.Events.DISCOVERED_SERVERS => NatsEvent.ServersDiscovered(url)
                       }
                       offer(event)
                     }
                       .errorListener(new ErrorListener {
                         override def errorOccurred(conn: JConnection, error: String): Unit =
                           offer(NatsEvent.Error(error))
                         override def exceptionOccurred(conn: JConnection, exp: Exception): Unit =
                           offer(NatsEvent.ExceptionOccurred(exp))
                       })
                   }
    } yield (stream, customizer)
}
