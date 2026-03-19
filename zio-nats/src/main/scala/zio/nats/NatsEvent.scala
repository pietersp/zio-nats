package zio.nats

/**
 * A connection lifecycle event emitted via [[Nats.lifecycleEvents]].
 *
 *   - `Connected` — initial connection established; `url` is the server URL.
 *   - `Disconnected` — connection lost; the client will attempt to reconnect.
 *   - `Reconnected` — TCP reconnect succeeded.
 *   - `Resubscribed` — subscriptions were re-established after a reconnect.
 *     Distinct from `Reconnected`: `Reconnected` fires when the TCP connection
 *     is restored, `Resubscribed` fires when all SUB commands have been re-sent
 *     to the server.
 *   - `ServersDiscovered` — the server advertised new cluster members (signal
 *     only; jnats 2.x does not expose the discovered URLs in the listener
 *     callback).
 *   - `Closed` — the connection was permanently closed (no more reconnects).
 *   - `LameDuckMode` — the server is shutting down gracefully; migrate to
 *     another server.
 *   - `Error` — a non-fatal error string was reported by the server.
 *   - `ExceptionOccurred` — an exception was raised by the jnats connection.
 */
enum NatsEvent {
  case Connected(url: String)
  case Disconnected(url: String)
  case Reconnected(url: String)
  case Resubscribed(url: String)
  case ServersDiscovered
  case Closed
  case LameDuckMode
  case Error(message: String)
  case ExceptionOccurred(ex: Throwable)
}
