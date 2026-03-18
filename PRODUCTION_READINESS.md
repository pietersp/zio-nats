# Production-Readiness Checklist

## Critical — Crash / Data-Loss Bugs

### 1. `NatsRpc.respond` kills the subscription on any handler error

`NatsRpc.respond` (`NatsRpc.scala:59`) runs `mapZIO` on the raw stream with no error recovery. A single decode failure or handler failure terminates the entire subscription stream. Callers are not warned and must re-subscribe. The stream should continue after per-message failures, logging or NAK'ing the bad message.

---

## High — Correctness Gaps

### 2. Thin error-path test coverage

No tests verify that `DecodingError`, `ConnectionFailed`, `Timeout`, `JetStreamApiError`, or `KeyValueOperationFailed` are surfaced correctly. Only happy paths are tested.

### 3. No concurrency or stress tests

No test with concurrent publishers/subscribers, no back-pressure test, no test for the lifecycle event hub under load.

### 4. `ZIO.sleep(500.millis)` subscription setup in tests

`NatsPubSubSpec` and others use fixed sleeps to wait for subscriptions to become ready. This is fragile (slow CI = flake). Use `Promise`-based synchronization instead.

---

## Medium — Missing Features / Quality

### 5. No TLS/mTLS fields in `NatsConfig`

TLS requires falling back to the `optionsCustomizer` escape hatch. Production NATS clusters almost always use TLS. First-class fields (`tlsContext: Option[SSLContext]`, `tlsFirst: Boolean`) would avoid leaking jnats types into user config code.

### 6. `credentialPath: Option[String]` should be `Option[java.nio.file.Path]`

Type safety improvement; catches invalid paths at construction rather than at connection time.

### 7. `NatsConfig` not re-exported from `package.scala`

Users need a separate `import zio.nats.config.NatsConfig`. Should follow the same re-export pattern as all other types:

```scala
type NatsConfig = config.NatsConfig; val NatsConfig = config.NatsConfig
```

### 8. `RESUBSCRIBED` and `RECONNECTED` both map to `NatsEvent.Reconnected`

`Nats.scala` maps jnats `RESUBSCRIBED` → `NatsEvent.Reconnected` (same as `RECONNECTED`). These are distinct events; `RESUBSCRIBED` means subscriptions were re-established after a reconnect. Consider a separate `NatsEvent.Resubscribed` case, or at minimum add a ScalaDoc note.

### 9. `DISCOVERED_SERVERS` event emits current connection URL, not discovered servers

`Nats.scala:213` passes `conn.getConnectedUrl()` to `NatsEvent.ServersDiscovered`. This is the current connection URL, not the list of newly discovered servers. The value is misleading.

### 10. `ConnectionStatus` missing `DRAINING`

jnats `Connection.Status` has `DRAINING_SUBS` / `DRAINING_PUBS` states. `ConnectionStatus.fromJava` falls through to `Disconnected` for these. Users calling `drain()` will observe `Disconnected` mid-drain rather than a meaningful draining state.

---

## Low — Polish / Project Hygiene

### 11. No `CHANGELOG.md` / versioning policy

No record of what changed between versions. Needed before a 1.0 release.

### 12. No `SECURITY.md`

Describes how to report vulnerabilities responsibly.
