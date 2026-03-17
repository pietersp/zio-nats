package zio.nats

/**
 * A connection lifecycle event emitted via [[Nats.lifecycleEvents]].
 *
 *   - `Connected`         — initial connection established; `url` is the server URL.
 *   - `Disconnected`      — connection lost; the client will attempt to reconnect.
 *   - `Reconnected`       — reconnect succeeded.
 *   - `ServersDiscovered` — the server advertised new cluster members.
 *   - `Closed`            — the connection was permanently closed (no more reconnects).
 *   - `LameDuckMode`      — the server is shutting down gracefully; migrate to another server.
 *   - `Error`             — a non-fatal error string was reported by the server.
 *   - `ExceptionOccurred` — an exception was raised by the jnats connection.
 */
enum NatsEvent {
  case Connected(url: String)
  case Disconnected(url: String)
  case Reconnected(url: String)
  case ServersDiscovered(url: String)
  case Closed
  case LameDuckMode
  case Error(message: String)
  case ExceptionOccurred(ex: Throwable)
}
