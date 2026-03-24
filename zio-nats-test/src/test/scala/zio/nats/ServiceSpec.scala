package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object ServiceSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Throwable] = suite("Service Framework")(
    test("service starts and is discoverable via ping") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("hello").in[String].out[String]
          svc  <- nats.service(
                   ServiceConfig("ping-test", "1.0.0"),
                   ep.handle(name => ZIO.succeed(s"Hi $name"))
                 )
          discovery <- ServiceDiscovery.make(maxWait = 3.seconds, maxResults = 5)
          responses <- discovery.ping("ping-test")
        } yield assertTrue(
          responses.nonEmpty,
          responses.exists(_.name == "ping-test"),
          responses.exists(_.version == "1.0.0"),
          svc.name == "ping-test",
          svc.id.nonEmpty
        )
      }
    },

    test("echo endpoint round-trips a String payload") {
      ZIO.scoped {
        for {
          nats  <- ZIO.service[Nats]
          ep     = ServiceEndpoint("echo").in[String].out[String]
          _     <- nats.service(ServiceConfig("echo-svc", "1.0.0"), ep.handle(ZIO.succeed(_)))
          _     <- ZIO.sleep(200.millis)
          reply <- nats.request[String, String](Subject("echo"), "hello", 5.seconds)
        } yield assertTrue(reply.value == "hello")
      }
    },

    test("multi-endpoint service routes requests to the correct handler") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          greet = ServiceEndpoint("greet").in[String].out[String]
          shout = ServiceEndpoint("shout").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("multi-svc", "1.0.0"),
                 greet.handle(name => ZIO.succeed(s"Hello, $name")),
                 shout.handle(name => ZIO.succeed(name.toUpperCase))
               )
          _  <- ZIO.sleep(200.millis)
          r1 <- nats.request[String, String](Subject("greet"), "world", 5.seconds)
          r2 <- nats.request[String, String](Subject("shout"), "world", 5.seconds)
        } yield assertTrue(r1.value == "Hello, world", r2.value == "WORLD")
      }
    },

    test("grouped endpoints use the group subject prefix") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          asc   = ServiceEndpoint("ascending").inGroup("sort").in[String].out[String]
          desc  = ServiceEndpoint("descending").inGroup("sort").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("sort-svc", "1.0.0"),
                 asc.handle(s => ZIO.succeed(s.split(",").sorted.mkString(","))),
                 desc.handle(s => ZIO.succeed(s.split(",").sorted.reverse.mkString(",")))
               )
          _  <- ZIO.sleep(200.millis)
          r1 <- nats.request[String, String](Subject("sort.ascending"), "c,a,b", 5.seconds)
          r2 <- nats.request[String, String](Subject("sort.descending"), "c,a,b", 5.seconds)
        } yield assertTrue(r1.value == "a,b,c", r2.value == "c,b,a")
      }
    },

    test("requestService returns typed reply on success") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("typed-ok").in[String].out[String].failsWith[String]
          _    <- nats.service(
                 ServiceConfig("typed-ok-svc", "1.0.0"),
                 ep.handle(s => ZIO.succeed(s.toUpperCase))
               )
          _      <- ZIO.sleep(200.millis)
          result <- nats
                      .requestService(ep, "hello", 5.seconds)
                      .mapError(e => new RuntimeException(e.toString))
        } yield assertTrue(result == "HELLO")
      }
    },

    test("requestService fails with typed domain error") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("typed-fail").in[String].out[String].failsWith[String]
          _    <- nats.service(
                 ServiceConfig("typed-fail-svc", "1.0.0"),
                 ep.handle(_ => ZIO.fail("intentional error"))
               )
          _      <- ZIO.sleep(200.millis)
          result <- nats.requestService(ep, "x", 5.seconds).either
        } yield assertTrue(result == Left("intentional error"))
      }
    },

    test("Nats.request on a fallible endpoint still fails with ServiceCallFailed") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("raw-fail").in[String].out[String].failsWith[String]
          _    <- nats.service(
                 ServiceConfig("raw-fail-svc", "1.0.0"),
                 ep.handle(_ => ZIO.fail("intentional error"))
               )
          _ <- ZIO.sleep(200.millis)
          // Untyped caller: still gets ServiceCallFailed, not the decoded error
          result <- nats.request[String, String](Subject("raw-fail"), "x", 5.seconds).either
        } yield assertTrue(result match {
          case Left(_: NatsError.ServiceCallFailed) => true
          case _                                    => false
        })
      }
    },

    test("requestService with grouped endpoint routes to the correct subject") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("ping").inGroup("ops").in[String].out[String].failsWith[String]
          _    <- nats.service(
                 ServiceConfig("ops-svc", "1.0.0"),
                 ep.handle(s => ZIO.succeed(s"pong:$s"))
               )
          _      <- ZIO.sleep(200.millis)
          result <- nats
                      .requestService(ep, "test", 5.seconds)
                      .mapError(e => new RuntimeException(e.toString))
        } yield assertTrue(result == "pong:test")
      }
    },

    test("infallible handler (Nothing error type) compiles and works") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("timestamp").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("clock-svc", "1.0.0"),
                 ep.handle(_ => Clock.instant.map(_.toString))
               )
          _     <- ZIO.sleep(200.millis)
          reply <- nats.request[String, String](Subject("timestamp"), "now", 5.seconds)
        } yield assertTrue(reply.value.nonEmpty)
      }
    },

    test("handleWith provides subject and headers") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("inspect").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("inspect-svc", "1.0.0"),
                 ep.handleWith { req =>
                   val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
                   ZIO.succeed(s"subj=${req.subject.value} trace=$traceId payload=${req.value}")
                 }
               )
          _     <- ZIO.sleep(200.millis)
          reply <- nats.request[String, String](Subject("inspect"), "test", 5.seconds)
        } yield assertTrue(
          reply.value.contains("subj=inspect"),
          reply.value.contains("payload=test")
        )
      }
    },

    test("stats reflect request count after handling requests") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("counted").in[String].out[String]
          svc  <- nats.service(
                   ServiceConfig("stats-svc", "1.0.0"),
                   ep.handle(ZIO.succeed(_))
                 )
          _ <- ZIO.sleep(200.millis)
          _ <- nats.request[String, String](Subject("counted"), "a", 5.seconds)
          _ <- nats.request[String, String](Subject("counted"), "b", 5.seconds)
          _ <- ZIO.sleep(200.millis)
          s <- svc.stats
        } yield assertTrue(
          s.endpoints.exists(e => e.name == "counted" && e.numRequests >= 2)
        )
      }
    },

    test("reset clears endpoint statistics counters") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("resetme").in[String].out[String]
          svc  <- nats.service(
                   ServiceConfig("reset-svc", "1.0.0"),
                   ep.handle(ZIO.succeed(_))
                 )
          _ <- ZIO.sleep(200.millis)
          _ <- nats.request[String, String](Subject("resetme"), "x", 5.seconds)
          _ <- ZIO.sleep(100.millis)
          _ <- svc.reset
          _ <- ZIO.sleep(100.millis)
          s <- svc.stats
        } yield assertTrue(
          s.endpoints.forall(_.numRequests == 0)
        )
      }
    },

    test("service stops cleanly when scope closes") {
      for {
        nats <- ZIO.service[Nats]
        ep    = ServiceEndpoint("transient").in[String].out[String]
        _    <- ZIO.scoped {
               nats.service(
                 ServiceConfig("transient-svc", "1.0.0"),
                 ep.handle(ZIO.succeed(_))
               )
             }
        // After scope closes the service is unregistered; request should time out
        result <- nats.request[String, String](Subject("transient"), "x", 500.millis).either
      } yield assertTrue(result.isLeft)
    },

    test("discovery info returns endpoint list") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep1   = ServiceEndpoint("ep-a").in[String].out[String]
          ep2   = ServiceEndpoint("ep-b").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("info-svc", "1.0.0", description = Some("test service")),
                 ep1.handle(ZIO.succeed(_)),
                 ep2.handle(ZIO.succeed(_))
               )
          _         <- ZIO.sleep(200.millis)
          discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
          responses <- discovery.info("info-svc")
        } yield assertTrue(
          responses.nonEmpty,
          responses.head.description.contains("test service"),
          responses.head.endpoints.map(_.name).toSet.intersect(Set("ep-a", "ep-b")).nonEmpty
        )
      }
    },

    test("discovery stats returns per-endpoint stats") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("stat-ep").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("disc-stats-svc", "1.0.0"),
                 ep.handle(ZIO.succeed(_))
               )
          _         <- ZIO.sleep(200.millis)
          _         <- nats.request[String, String](Subject("stat-ep"), "ping", 5.seconds)
          _         <- ZIO.sleep(100.millis)
          discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
          responses <- discovery.stats("disc-stats-svc")
        } yield assertTrue(
          responses.nonEmpty,
          responses.flatMap(_.endpoints).exists(_.numRequests >= 1)
        )
      }
    }
  ).provideShared(
    NatsTestLayers.nats
  ) @@ sequential @@ withLiveClock @@ timeout(120.seconds)
}
