package zio.nats

import zio.*
import zio.nats.config.*
import zio.test.*
import java.nio.file.Paths

/**
 * Pure unit tests for [[NatsConfig]] ZIO Config descriptors. No Docker/NATS
 * server required; all tests are offline.
 */
object NatsConfigSpec extends ZIOSpecDefault {

  // Use "|" as seqDelim because NATS server URLs contain ":" and ConfigProvider.fromMap
  // splits list values at seqDelim.  "|" never appears in nats:// URLs.
  private def loadConfig(map: Map[String, String]): IO[Config.Error, NatsConfig] =
    ZIO
      .config(NatsConfig.config.nested("nats"))
      .provide(Runtime.setConfigProvider(ConfigProvider.fromMap(map, pathDelim = ".", seqDelim = "|")))

  def spec: Spec[Any, Config.Error] = suite("NatsConfig ZIO Config descriptors")(
    suite("defaults")(
      test("empty map produces NatsConfig.default") {
        loadConfig(Map.empty).map { cfg =>
          assertTrue(cfg == NatsConfig.default)
        }
      }
    ),

    suite("servers")(
      // ConfigProvider.fromMap uses seqDelim ("|") to split list values.
      test("single server") {
        loadConfig(Map("nats.servers" -> "nats://broker:4222")).map { cfg =>
          assertTrue(cfg.servers == List("nats://broker:4222"))
        }
      },
      test("multiple servers") {
        loadConfig(Map("nats.servers" -> "nats://a:4222|nats://b:4222")).map { cfg =>
          assertTrue(cfg.servers == List("nats://a:4222", "nats://b:4222"))
        }
      },
      test("invalid server URL fails at load time with a helpful error") {
        loadConfig(Map("nats.servers" -> "not-a-url")).exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(result.causeOption.exists(_.squash.getMessage.contains("not-a-url")))
        }
      }
    ),

    suite("connection-name")(
      test("absent defaults to None") {
        loadConfig(Map.empty).map(cfg => assertTrue(cfg.connectionName.isEmpty))
      },
      test("present sets the name") {
        loadConfig(Map("nats.connection-name" -> "myapp")).map { cfg =>
          assertTrue(cfg.connectionName == Some("myapp"))
        }
      }
    ),

    suite("scalar fields")(
      test("duration field uses ISO-8601 format") {
        loadConfig(Map("nats.connection-timeout" -> "PT5S")).map { cfg =>
          assertTrue(cfg.connectionTimeout == 5.seconds)
        }
      },
      test("max-reconnects = -1 means unlimited") {
        loadConfig(Map("nats.max-reconnects" -> "-1")).map { cfg =>
          assertTrue(cfg.maxReconnects == -1)
        }
      },
      test("reconnect-buffer-size = 0 disables buffering") {
        loadConfig(Map("nats.reconnect-buffer-size" -> "0")).map { cfg =>
          assertTrue(cfg.reconnectBufferSize == 0L)
        }
      },
      test("socket-read-timeout uses ISO-8601 duration") {
        loadConfig(Map("nats.socket-read-timeout" -> "PT0.5S")).map { cfg =>
          assertTrue(cfg.socketReadTimeout == 500.millis)
        }
      }
    ),

    suite("auth")(
      test("no-auth variant") {
        loadConfig(Map("nats.auth.type" -> "no-auth")).map { cfg =>
          assertTrue(cfg.auth == NatsAuth.NoAuth)
        }
      },
      test("token variant") {
        loadConfig(
          Map(
            "nats.auth.type"  -> "token",
            "nats.auth.value" -> "s3cr3t"
          )
        ).map { cfg =>
          assertTrue(cfg.auth == NatsAuth.Token("s3cr3t"))
        }
      },
      test("user-password variant") {
        loadConfig(
          Map(
            "nats.auth.type"     -> "user-password",
            "nats.auth.username" -> "alice",
            "nats.auth.password" -> "p4ss"
          )
        ).map { cfg =>
          assertTrue(cfg.auth == NatsAuth.UserPassword("alice", "p4ss"))
        }
      },
      test("credential-file variant") {
        loadConfig(
          Map(
            "nats.auth.type" -> "credential-file",
            "nats.auth.path" -> "/run/secrets/nats.creds"
          )
        ).map { cfg =>
          assertTrue(cfg.auth == NatsAuth.CredentialFile(Paths.get("/run/secrets/nats.creds")))
        }
      },
      test("absent auth section defaults to NoAuth") {
        loadConfig(Map.empty).map { cfg =>
          assertTrue(cfg.auth == NatsAuth.NoAuth)
        }
      },
      test("unrecognised auth.type fails with a helpful error") {
        loadConfig(Map("nats.auth.type" -> "tokne")).exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(result.causeOption.exists(_.squash.getMessage.contains("tokne")))
        }
      },
      test("missing required sub-field fails with a helpful error") {
        loadConfig(Map("nats.auth.type" -> "token" /* auth.value missing */ )).exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(result.causeOption.exists(_.squash.getMessage.contains("auth.value")))
        }
      }
    ),

    suite("tls")(
      test("disabled variant") {
        loadConfig(Map("nats.tls.type" -> "disabled")).map { cfg =>
          assertTrue(cfg.tls == NatsTls.Disabled)
        }
      },
      test("system-default variant") {
        loadConfig(Map("nats.tls.type" -> "system-default")).map { cfg =>
          assertTrue(cfg.tls == NatsTls.SystemDefault)
        }
      },
      test("key-store variant — required fields only") {
        loadConfig(
          Map(
            "nats.tls.type"               -> "key-store",
            "nats.tls.key-store-path"     -> "/certs/client.jks",
            "nats.tls.key-store-password" -> "changeit"
          )
        ).map { cfg =>
          assertTrue(
            cfg.tls == NatsTls.KeyStore(
              keyStorePath = Paths.get("/certs/client.jks"),
              keyStorePassword = "changeit"
            )
          )
        }
      },
      test("key-store variant — all optional fields") {
        loadConfig(
          Map(
            "nats.tls.type"                 -> "key-store",
            "nats.tls.key-store-path"       -> "/certs/client.jks",
            "nats.tls.key-store-password"   -> "changeit",
            "nats.tls.trust-store-path"     -> "/certs/trust.jks",
            "nats.tls.trust-store-password" -> "trustpass",
            "nats.tls.algorithm"            -> "TLSv1.3",
            "nats.tls.tls-first"            -> "true"
          )
        ).map { cfg =>
          assertTrue(
            cfg.tls == NatsTls.KeyStore(
              keyStorePath = Paths.get("/certs/client.jks"),
              keyStorePassword = "changeit",
              trustStorePath = Some(Paths.get("/certs/trust.jks")),
              trustStorePassword = Some("trustpass"),
              algorithm = Some("TLSv1.3"),
              tlsFirst = true
            )
          )
        }
      },
      test("absent tls section defaults to Disabled") {
        loadConfig(Map.empty).map { cfg =>
          assertTrue(cfg.tls == NatsTls.Disabled)
        }
      },
      test("unrecognised tls.type fails with a helpful error") {
        loadConfig(Map("nats.tls.type" -> "system_default")).exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(result.causeOption.exists(_.squash.getMessage.contains("system_default")))
        }
      },
      test("missing required key-store fields fails with a helpful error") {
        loadConfig(Map("nats.tls.type" -> "key-store" /* both paths missing */ )).exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(result.causeOption.exists(_.squash.getMessage.contains("key-store-path")))
        }
      }
    )
  )
}
