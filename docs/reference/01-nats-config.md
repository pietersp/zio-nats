---
id: nats-config
title: NatsConfig Reference
---

# NatsConfig Reference

Full reference for all `NatsConfig` fields.

## Constructor

```scala
NatsConfig(
  // Connection
  servers                              = List("nats://localhost:4222"),
  connectionName                       = None,                   // Option[String]
  connectionTimeout                    = 2.seconds,
  reconnectWait                        = 2.seconds,
  maxReconnects                        = 60,                     // -1 = unlimited
  pingInterval                         = 2.minutes,
  requestCleanupInterval               = 5.seconds,
  bufferSize                           = 64 * 1024,              // bytes
  noEcho                               = false,
  utf8Support                          = false,
  inboxPrefix                          = "_INBOX.",

  // Authentication — pick one variant:
  auth                                 = NatsAuth.NoAuth,
  // auth                              = NatsAuth.Token("s3cr3t"),
  // auth                              = NatsAuth.UserPassword("alice", "p4ss"),
  // auth                              = NatsAuth.CredentialFile(Paths.get("/app/nats.creds")),
  // auth                              = NatsAuth.Custom(myAuthHandler),

  // TLS — pick one variant:
  tls                                  = NatsTls.Disabled,
  // tls                               = NatsTls.SystemDefault,
  // tls                               = NatsTls.KeyStore(keyStorePath = ..., keyStorePassword = "..."),
  // tls                               = NatsTls.Custom(mySSLContext),

  // Reconnect tuning
  reconnectJitter                      = 100.millis,
  reconnectJitterTls                   = 1.second,
  reconnectBufferSize                  = 8 * 1024 * 1024,        // bytes; 0 = disable

  // Outbound queue
  maxMessagesInOutgoingQueue           = 0,                      // 0 = unlimited
  discardMessagesWhenOutgoingQueueFull = false,
  writeQueuePushTimeout                = Duration.Zero,          // Zero = block indefinitely

  // Socket tuning
  socketWriteTimeout                   = Duration.Zero,
  socketReadTimeout                    = Duration.Zero,

  // Protocol
  maxControlLine                       = 0,                      // 0 = jnats default
  maxPingsOut                          = 2,

  // Lifecycle
  drainTimeout                         = 30.seconds
)
```

## Field reference

| Field | Type | Default | Notes |
|---|---|---|---|
| `servers` | `List[String]` | `List("nats://localhost:4222")` | Multiple servers for clustering / failover |
| `connectionName` | `Option[String]` | `None` | Shown in server connection logs |
| `connectionTimeout` | `Duration` | `2.seconds` | Max time to establish TCP connection |
| `reconnectWait` | `Duration` | `2.seconds` | Pause between reconnect attempts |
| `maxReconnects` | `Int` | `60` | `-1` for unlimited |
| `pingInterval` | `Duration` | `2.minutes` | How often to ping the server |
| `requestCleanupInterval` | `Duration` | `5.seconds` | How often to purge timed-out request inboxes |
| `bufferSize` | `Int` | `65536` | Incoming message buffer in bytes |
| `noEcho` | `Boolean` | `false` | Suppress messages the connection published itself |
| `utf8Support` | `Boolean` | `false` | Enable UTF-8 subject names |
| `inboxPrefix` | `String` | `"_INBOX."` | Prefix for ephemeral reply subjects |
| `auth` | `NatsAuth` | `NoAuth` | Authentication method — see below |
| `tls` | `NatsTls` | `Disabled` | TLS mode — see below |
| `reconnectJitter` | `Duration` | `100.millis` | Random jitter added to reconnect wait (plain) |
| `reconnectJitterTls` | `Duration` | `1.second` | Random jitter added to reconnect wait (TLS) |
| `reconnectBufferSize` | `Long` | `8388608` | Bytes buffered during reconnect; `0` = disabled |
| `maxMessagesInOutgoingQueue` | `Int` | `0` | `0` = unlimited outbound queue |
| `discardMessagesWhenOutgoingQueueFull` | `Boolean` | `false` | Drop messages instead of blocking |
| `writeQueuePushTimeout` | `Duration` | `Duration.Zero` | `Zero` = block indefinitely |
| `socketWriteTimeout` | `Duration` | `Duration.Zero` | `Zero` = OS default |
| `socketReadTimeout` | `Duration` | `Duration.Zero` | `Zero` = OS default |
| `maxControlLine` | `Int` | `0` | `0` = jnats default (4096 bytes) |
| `maxPingsOut` | `Int` | `2` | Unanswered pings before disconnect |
| `drainTimeout` | `Duration` | `30.seconds` | Max time to drain subscriptions on close |

## NatsAuth variants

| Variant | When to use |
|---|---|
| `NatsAuth.NoAuth` | Anonymous server — default |
| `NatsAuth.Token(token)` | Static token authentication |
| `NatsAuth.UserPassword(user, pass)` | Username + password authentication |
| `NatsAuth.CredentialFile(path)` | NKey/JWT credentials file (e.g. Synadia Cloud / NGS) |
| `NatsAuth.Custom(handler)` | Runtime `AuthHandler` — for dynamic credentials or Vault integration |

## NatsTls variants

| Variant | When to use |
|---|---|
| `NatsTls.Disabled` | No TLS — default |
| `NatsTls.SystemDefault` | Server has a public CA cert; use the JVM trust store |
| `NatsTls.KeyStore(...)` | mTLS with JVM keystore/truststore files |
| `NatsTls.Custom(sslCtx)` | Pre-built `SSLContext` — for certs loaded at runtime |

`NatsTls.KeyStore` fields: `keyStorePath`, `keyStorePassword`, `trustStorePath` (optional),
`trustStorePassword` (optional), `tlsFirst` (default `false`).

## Convenience constructors

| Constructor | Equivalent to |
|---|---|
| `NatsConfig.default` | `NatsConfig()` — localhost, no auth, no TLS |
| `NatsConfig("nats://host:4222")` | `NatsConfig(servers = List("nats://host:4222"))` |
| `Nats.default` (ZLayer) | `ZLayer.succeed(NatsConfig.default) >>> Nats.live` |

## Environment variable names

When using `NatsConfig.fromConfig`, keys are mapped to `NATS_UPPER_SNAKE_CASE`:

| Field | Environment variable |
|---|---|
| `servers` | `NATS_SERVERS` (comma-separated for multiple) |
| `auth.type` | `NATS_AUTH_TYPE` (`no-auth`, `token`, `user-password`, `credential-file`) |
| `auth.value` | `NATS_AUTH_VALUE` (for `token`) |
| `auth.username` | `NATS_AUTH_USERNAME` |
| `auth.password` | `NATS_AUTH_PASSWORD` |
| `tls.type` | `NATS_TLS_TYPE` (`disabled`, `system-default`, `key-store`) |
| `connection-timeout` | `NATS_CONNECTION_TIMEOUT` (ISO-8601 duration, e.g. `PT5S`) |
| `max-reconnects` | `NATS_MAX_RECONNECTS` |
| `socket-read-timeout` | `NATS_SOCKET_READ_TIMEOUT` |

## Migration from previous versions

If you used `NatsConfig` before the auth/TLS ADT redesign:

| Before | After |
|---|---|
| `NatsConfig(token = Some("x"))` | `NatsConfig(auth = NatsAuth.Token("x"))` |
| `NatsConfig(username = Some("u"), password = Some("p"))` | `NatsConfig(auth = NatsAuth.UserPassword("u", "p"))` |
| `NatsConfig(credentialPath = Some(p))` | `NatsConfig(auth = NatsAuth.CredentialFile(p))` |
| `NatsConfig(authHandler = Some(h))` | `NatsConfig(auth = NatsAuth.Custom(h))` |
| `NatsConfig(tlsFirst = true)` | `NatsConfig(tls = NatsTls.KeyStore(..., tlsFirst = true))` |
