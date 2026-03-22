---
id: configuration
title: Configuration
---

# Configuration

> Connect to NATS with authentication, TLS, environment variables, or HOCON.

All connection settings live in `NatsConfig`. Build it directly, load it from environment
variables, or load it from a HOCON file — the `Nats.live` layer accepts any of these.

## Prerequisites

- [Quick start](../quickstart) completed

## Default config

```scala mdoc:silent
import zio.*
import zio.nats.*

// localhost:4222, no auth, no TLS
val defaultLayer  = ZLayer.succeed(NatsConfig.default) >>> Nats.live

// Shorthand — equivalent to the above
val shorthand     = Nats.default

// Single server
val singleServer  = ZLayer.succeed(NatsConfig("nats://broker:4222")) >>> Nats.live

// Multiple servers (clustering / failover)
val clustered = ZLayer.succeed(
  NatsConfig(servers = List("nats://a:4222", "nats://b:4222", "nats://c:4222"))
) >>> Nats.live
```

## Authentication

Authentication and TLS are independent settings on `NatsConfig`. Pick one `NatsAuth` variant:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.config.*
import java.nio.file.Paths

// Anonymous (default)
val noAuth = NatsConfig()

// Static token
val token = NatsConfig(auth = NatsAuth.Token("s3cr3t"))

// Username + password
val userPass = NatsConfig(auth = NatsAuth.UserPassword("alice", "p4ssw0rd"))

// NKey/JWT from a .creds file (e.g. Synadia Cloud / NGS)
val creds = NatsConfig(auth = NatsAuth.CredentialFile(Paths.get("/run/secrets/nats.creds")))
```

For dynamic credential rotation or credentials that cannot be expressed as static text (e.g.
NKey signing with a key fetched from Vault), use `NatsAuth.Custom(myAuthHandler)` and pass
a jnats `AuthHandler` built outside of zio-nats.

Auth variants are mutually exclusive by construction — `NatsAuth` is a sealed ADT, so you
cannot accidentally combine two auth methods.

## TLS

Pick one `NatsTls` variant:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.config.*
import java.nio.file.Paths

// No TLS (default)
val noTls = NatsConfig()

// TLS using the JVM's system trust store — works with servers that have public CA certs
val systemTls = NatsConfig(tls = NatsTls.SystemDefault)

// mTLS via JVM keystore/truststore files
val mtls = NatsConfig(tls = NatsTls.KeyStore(
  keyStorePath       = Paths.get("/certs/client.jks"),
  keyStorePassword   = "changeit",
  trustStorePath     = Some(Paths.get("/certs/ca.jks")),
  trustStorePassword = Some("changeit")
))
```

For certificates loaded at runtime (e.g. fetched from AWS Secrets Manager or an HSM), use
`NatsTls.Custom(mySSLContext)` and pass a pre-built `javax.net.ssl.SSLContext`.

Auth and TLS operate at different layers: TLS secures the TCP connection; auth identifies your
client to the NATS permission system. Use either, both, or neither independently.

## Loading from environment variables

`NatsConfig.fromConfig` is a `ZLayer` that reads settings from ZIO's built-in config system.
Replace `NatsConfig.live` with it — no other change required:

```scala mdoc:silent
import zio.*
import zio.nats.*

val envApp: ZIO[Any, Throwable, Unit] =
  ZIO.unit.provide(Nats.live, NatsConfig.fromConfig)
```

With the default `ConfigProvider` (environment variables), keys are normalised to
`UPPER_SNAKE_CASE` under the `NATS_` prefix:

| Setting | Environment variable |
|---|---|
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

All fields have defaults — only values you want to override need to be set.

**Duration format** — ISO-8601: `PT5S` = 5 seconds, `PT2M` = 2 minutes, `PT0.1S` = 100 ms.

**Auth type values**: `no-auth`, `token`, `user-password`, `credential-file`.

**TLS type values**: `disabled`, `system-default`, `key-store`.

## Loading from HOCON

Add `dev.zio::zio-config-typesafe` and install `TypesafeConfigProvider` in your `bootstrap`:

```scala
// build.sbt
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

Then in `src/main/resources/application.conf`:

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

## Custom nesting

`NatsConfig.fromConfig` reads keys at the root level (no `nats.` prefix by default). If your
config lives under a different namespace, apply `.nested`:

```scala mdoc:silent
import zio.*
import zio.nats.*

// Config lives at myapp.nats.servers, myapp.nats.auth.type, etc.
val nestedConfig: ZLayer[Any, Config.Error, NatsConfig] =
  ZLayer.fromZIO(
    ZIO.config(NatsConfig.config.nested("nats").nested("myapp"))
  )
```

`.nested` calls stack from inner to outer — the last call becomes the outermost namespace.

## Programmatic config

`NatsTls.Custom` and `NatsAuth.Custom` hold runtime Java objects and are intentionally not
reachable from text config. Build `NatsConfig` directly when you need them:

```scala mdoc:silent
import zio.*
import zio.nats.*
import zio.nats.config.*
import javax.net.ssl.SSLContext
import io.nats.client.AuthHandler

def buildLayer(sslCtx: SSLContext, authHandler: AuthHandler) =
  ZLayer.succeed(
    NatsConfig(
      auth = NatsAuth.Custom(authHandler),
      tls  = NatsTls.Custom(sslCtx)
    )
  ) >>> Nats.live
```

## Next steps

- [NatsConfig reference](../reference/01-nats-config) — full field table with types and defaults
- [Architecture](../concepts/01-architecture) — how `NatsConfig` feeds into the service graph
- [Connection Events guide](./07-connection-events) — observe reconnection and disconnect events
