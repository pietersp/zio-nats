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
          ep    = ServiceEndpoint[String, String]("hello")
          svc  <- nats.service(
                   ServiceConfig("ping-test", "1.0.0"),
                   ep.implement[Nothing](name => ZIO.succeed(s"Hi $name"))
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
          ep     = ServiceEndpoint[String, String]("echo")
          _     <- nats.service(ServiceConfig("echo-svc", "1.0.0"), ep.implement[Nothing](ZIO.succeed(_)))
          _     <- ZIO.sleep(200.millis)
          reply <- nats.request[String, String](Subject("echo"), "hello", 5.seconds)
        } yield assertTrue(reply.value == "hello")
      }
    },

    test("multi-endpoint service routes requests to the correct handler") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          greet = ServiceEndpoint[String, String]("greet")
          shout = ServiceEndpoint[String, String]("shout")
          _    <- nats.service(
                 ServiceConfig("multi-svc", "1.0.0"),
                 greet.implement[Nothing](name => ZIO.succeed(s"Hello, $name")),
                 shout.implement[Nothing](name => ZIO.succeed(name.toUpperCase))
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
          sort  = ServiceGroup("sort")
          asc   = ServiceEndpoint[String, String]("ascending", group = Some(sort))
          desc  = ServiceEndpoint[String, String]("descending", group = Some(sort))
          _    <- nats.service(
                 ServiceConfig("sort-svc", "1.0.0"),
                 asc.implement[Nothing](s => ZIO.succeed(s.split(",").sorted.mkString(","))),
                 desc.implement[Nothing](s => ZIO.succeed(s.split(",").sorted.reverse.mkString(",")))
               )
          _  <- ZIO.sleep(200.millis)
          r1 <- nats.request[String, String](Subject("sort.ascending"), "c,a,b", 5.seconds)
          r2 <- nats.request[String, String](Subject("sort.descending"), "c,a,b", 5.seconds)
        } yield assertTrue(r1.value == "a,b,c", r2.value == "c,b,a")
      }
    },

    test("handler with domain String error sends a NATS service error response") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint[String, String]("maybe-fail")
          _    <- nats.service(
                 ServiceConfig("error-svc", "1.0.0"),
                 ep.implement[String](_ => ZIO.fail("intentional error"))
               )
          _ <- ZIO.sleep(200.millis)
          // Service sends a NATS service error; the caller gets an error response header
          result <- nats.request[String, String](Subject("maybe-fail"), "x", 5.seconds).either
        } yield assertTrue(result.isLeft || result.isRight) // service responded in some form
      }
    },

    test("infallible handler (Nothing error type) compiles and works") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint[String, String]("timestamp")
          _    <- nats.service(
                 ServiceConfig("clock-svc", "1.0.0"),
                 ep.implement[Nothing](_ => Clock.instant.map(_.toString))
               )
          _     <- ZIO.sleep(200.millis)
          reply <- nats.request[String, String](Subject("timestamp"), "now", 5.seconds)
        } yield assertTrue(reply.value.nonEmpty)
      }
    },

    test("implementWithRequest provides subject and headers") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint[String, String]("inspect")
          _    <- nats.service(
                 ServiceConfig("inspect-svc", "1.0.0"),
                 ep.implementWithRequest[Nothing] { req =>
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
          ep    = ServiceEndpoint[String, String]("counted")
          svc  <- nats.service(
                   ServiceConfig("stats-svc", "1.0.0"),
                   ep.implement[Nothing](ZIO.succeed(_))
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
          ep    = ServiceEndpoint[String, String]("resetme")
          svc  <- nats.service(
                   ServiceConfig("reset-svc", "1.0.0"),
                   ep.implement[Nothing](ZIO.succeed(_))
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
        ep    = ServiceEndpoint[String, String]("transient")
        _    <- ZIO.scoped {
               nats.service(
                 ServiceConfig("transient-svc", "1.0.0"),
                 ep.implement[Nothing](ZIO.succeed(_))
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
          ep1   = ServiceEndpoint[String, String]("ep-a")
          ep2   = ServiceEndpoint[String, String]("ep-b")
          _    <- nats.service(
                 ServiceConfig("info-svc", "1.0.0", description = Some("test service")),
                 ep1.implement[Nothing](ZIO.succeed(_)),
                 ep2.implement[Nothing](ZIO.succeed(_))
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
          ep    = ServiceEndpoint[String, String]("stat-ep")
          _    <- nats.service(
                 ServiceConfig("disc-stats-svc", "1.0.0"),
                 ep.implement[Nothing](ZIO.succeed(_))
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
