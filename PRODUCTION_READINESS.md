# Production-Readiness Checklist

## Medium — Missing Features / Quality

### 1. No TLS/mTLS fields in `NatsConfig`

TLS requires falling back to the `optionsCustomizer` escape hatch. Production NATS clusters almost always use TLS. First-class fields (`tlsContext: Option[SSLContext]`, `tlsFirst: Boolean`) would avoid leaking jnats types into user config code.

### 2. `credentialPath: Option[String]` should be `Option[java.nio.file.Path]`

Type safety improvement; catches invalid paths at construction rather than at connection time.

### 3. `NatsConfig` not re-exported from `package.scala`

Users need a separate `import zio.nats.config.NatsConfig`. Should follow the same re-export pattern as all other types:

```scala
type NatsConfig = config.NatsConfig; val NatsConfig = config.NatsConfig
```

### 4. `RESUBSCRIBED` and `RECONNECTED` both map to `NatsEvent.Reconnected`

`Nats.scala` maps jnats `RESUBSCRIBED` → `NatsEvent.Reconnected` (same as `RECONNECTED`). These are distinct events; `RESUBSCRIBED` means subscriptions were re-established after a reconnect. Consider a separate `NatsEvent.Resubscribed` case, or at minimum add a ScalaDoc note.

### 5. `DISCOVERED_SERVERS` event emits current connection URL, not discovered servers

`Nats.scala:213` passes `conn.getConnectedUrl()` to `NatsEvent.ServersDiscovered`. This is the current connection URL, not the list of newly discovered servers. The value is misleading.

### 6. `ConnectionStatus` missing `DRAINING`

jnats `Connection.Status` has `DRAINING_SUBS` / `DRAINING_PUBS` states. `ConnectionStatus.fromJava` falls through to `Disconnected` for these. Users calling `drain()` will observe `Disconnected` mid-drain rather than a meaningful draining state.
