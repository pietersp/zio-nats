---
id: configuration
title: Configuration
---

`NatsConfig` holds every connection setting for the NATS client - server addresses, authentication, TLS, reconnect behaviour, and socket tuning. Build it directly in code for static deployments, or let ZIO's built-in config system load it from environment variables or a HOCON file for environment-driven ones. Either way, the same `Nats.live` layer accepts the result.

## Connecting to a server

`NatsConfig` defaults to `localhost:4222` with no authentication and no TLS - the right starting point for local development. For production, pass one or more server URLs. Multiple servers enable automatic failover: if the client loses its connection to one server it tries the next in the list:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

// localhost:4222, no auth, no TLS
val defaultLayer = Nats.default

// Single server
val single = ZLayer.succeed(NatsConfig("nats://broker:4222")) >>> Nats.live

// Multiple servers - automatic failover and load distribution
val clustered = ZLayer.succeed(
  NatsConfig(servers = List("nats://a:4222", "nats://b:4222", "nats://c:4222"))
) >>> Nats.live
```

## Authentication

`NatsAuth` is a sealed ADT with mutually exclusive variants - you cannot accidentally combine two auth methods. Set it on `NatsConfig` via the `auth` field:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.config.*
import java.nio.file.Paths

// Anonymous (default)
val noAuth = NatsConfig()

// Static token
val token = NatsConfig(auth = NatsAuth.Token("s3cr3t"))

// Username and password
val userPass = NatsConfig(auth = NatsAuth.UserPassword("alice", "p4ssw0rd"))

// NKey/JWT from a .creds file (Synadia Cloud / NGS)
val creds = NatsConfig(auth = NatsAuth.CredentialFile(Paths.get("/run/secrets/nats.creds")))
```

For dynamic credential rotation or credentials that cannot be expressed as static text - such as NKey signing with a key fetched from Vault - use `NatsAuth.Custom(myAuthHandler)` and pass a jnats `AuthHandler` built outside of zio-nats.

## TLS

`NatsTls` is a sealed ADT controlling how the TCP connection is secured. Set it on `NatsConfig` via the `tls` field. Auth and TLS operate independently: TLS secures the transport; auth identifies your client to the NATS permission system. Use either, both, or neither:

```scala mdoc:compile-only
import zio.*
import zio.nats.*
import zio.nats.config.*
import java.nio.file.Paths

// No TLS (default)
val noTls = NatsConfig()

// TLS using the JVM system trust store - works with public CA certificates
val systemTls = NatsConfig(tls = NatsTls.SystemDefault)

// mTLS with JVM keystore and truststore files
val mtls = NatsConfig(
  tls = NatsTls.KeyStore(
    keyStorePath       = Paths.get("/certs/client.jks"),
    keyStorePassword   = "changeit",
    trustStorePath     = Some(Paths.get("/certs/ca.jks")),
    trustStorePassword = Some("changeit")
  )
)
```

For certificates loaded at runtime from AWS Secrets Manager, an HSM, or similar, use `NatsTls.Custom(mySSLContext)` with a pre-built `javax.net.ssl.SSLContext`.

## Loading from the environment

`NatsConfig.fromConfig` is a `ZLayer` that reads settings from ZIO's built-in config system - no extra dependency required beyond ZIO itself. By default ZIO reads from environment variables, with keys normalised to `UPPER_SNAKE_CASE` under the `NATS_` prefix.

Use this in container and CI/CD deployments where connection details differ per environment and you want to avoid hardcoding them in source:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

val envApp: ZIO[Any, Throwable, Unit] =
  ZIO.serviceWithZIO[Nats](_.publish(Subject("shop.orders"), "order-123"))
    .provide(Nats.live, NatsConfig.fromConfig)
```

All fields have defaults - only the values you want to override need to be set:

| Setting | Environment variable |
|---------|----------------------|
| `servers` (single) | `NATS_SERVERS=nats://broker:4222` |
| `servers` (multiple) | `NATS_SERVERS=nats://a:4222,nats://b:4222` |
| `auth.type` | `NATS_AUTH_TYPE=token` |
| `auth.value` (token) | `NATS_AUTH_VALUE=s3cr3t` |
| `auth.username` | `NATS_AUTH_USERNAME=alice` |
| `auth.password` | `NATS_AUTH_PASSWORD=p4ss` |
| `tls.type` | `NATS_TLS_TYPE=system-default` |
| `connection-timeout` | `NATS_CONNECTION_TIMEOUT=PT5S` |
| `max-reconnects` | `NATS_MAX_RECONNECTS=60` |
| `socket-read-timeout` | `NATS_SOCKET_READ_TIMEOUT=PT0.5S` |

Durations use ISO-8601 format: `PT5S` = 5 seconds, `PT2M` = 2 minutes, `PT0.1S` = 100 ms. Auth type values: `no-auth`, `token`, `user-password`, `credential-file`. TLS type values: `disabled`, `system-default`, `key-store`.

## Loading from HOCON

If you prefer HOCON over environment variables - common for local development with `application.conf` - add the `zio-config-typesafe` dependency and install its config provider in `bootstrap`:

```scala
libraryDependencies += "dev.zio" %% "zio-config-typesafe" % "<zio-config-version>"
```

```scala
import zio.*
import zio.nats.*
import zio.config.typesafe.TypesafeConfigProvider

object MyApp extends ZIOAppDefault {
  override val bootstrap: ZLayer[Any, Config.Error, Unit] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  val run = ZIO.unit.provide(Nats.live, NatsConfig.fromConfig)
}
```

In `src/main/resources/application.conf`:

```hocon
nats {
  servers = ["nats://broker:4222"]
  connection-timeout = "PT5S"
  auth {
    type  = token
    value = ${?NATS_TOKEN}
  }
}
```

`NatsConfig.fromConfig` reads from the root level by default. If your config lives under a different namespace, use `.nested` to scope it - calls stack from inner to outer:

```scala mdoc:compile-only
import zio.*
import zio.nats.*

// Reads from myapp.nats.servers, myapp.nats.auth.type, etc.
val nestedConfig: ZLayer[Any, Config.Error, NatsConfig] =
  ZLayer.fromZIO(
    ZIO.config(NatsConfig.config.nested("nats").nested("myapp"))
  )
```

## Reconnect and timeout tuning

`NatsConfig` exposes several fields for controlling reconnect and timeout behaviour. The defaults suit most applications, but high-availability or latency-sensitive deployments may need to tune them:

| Field | Default | Effect |
|-------|---------|--------|
| `maxReconnects` | `60` | Maximum reconnect attempts before giving up and emitting `NatsEvent.Closed` |
| `reconnectWait` | `2s` | Pause between reconnect attempts |
| `connectionTimeout` | `2s` | How long to wait for the initial TCP connection |
| `drainTimeout` | `30s` | How long to wait when draining subscriptions on shutdown |
| `socketWriteTimeout` | `2s` | Write deadline per TCP write; prevents silent hangs |

Set these directly on `NatsConfig` when constructing it in code, or via the corresponding environment variables when using `NatsConfig.fromConfig`. See the [NatsConfig reference](../reference/01-nats-config.md) for the full field list.

## Next steps

- [NatsConfig reference](../reference/01-nats-config.md) - full field table with types and defaults
- [Layer Construction](../concepts/01-construction.md) - how `NatsConfig` feeds into the service layer
