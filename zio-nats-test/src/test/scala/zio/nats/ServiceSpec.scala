package zio.nats

import zio.*
import zio.nats.testkit.NatsTestLayers
import zio.test.*
import zio.test.TestAspect.*

object ServiceSpec extends ZIOSpecDefault {

  // Error types used by the union-error tests
  private case class NotFound(resource: String)
  private case class Forbidden(reason: String)
  private case class ServiceUnavailable(reason: String)
  private case class RateLimited(retryAfter: Int)
  private case class Conflict(detail: String)
  private case class Prefix(value: String)
  private case class Suffix(value: String)

  private given NatsCodec[NotFound] = new NatsCodec[NotFound]:
    def encode(e: NotFound): Chunk[Byte]                              = NatsCodec.stringCodec.encode(e.resource)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, NotFound] =
      NatsCodec.stringCodec.decode(bytes).map(NotFound(_))

  private given NatsCodec[Forbidden] = new NatsCodec[Forbidden]:
    def encode(e: Forbidden): Chunk[Byte]                              = NatsCodec.stringCodec.encode(e.reason)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, Forbidden] =
      NatsCodec.stringCodec.decode(bytes).map(Forbidden(_))

  private given NatsCodec[ServiceUnavailable] = new NatsCodec[ServiceUnavailable]:
    def encode(e: ServiceUnavailable): Chunk[Byte]                              = NatsCodec.stringCodec.encode(e.reason)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, ServiceUnavailable] =
      NatsCodec.stringCodec.decode(bytes).map(ServiceUnavailable(_))

  private given NatsCodec[RateLimited] = new NatsCodec[RateLimited]:
    def encode(e: RateLimited): Chunk[Byte]                              = NatsCodec.stringCodec.encode(e.retryAfter.toString)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, RateLimited] =
      NatsCodec.stringCodec
        .decode(bytes)
        .flatMap(s => s.toIntOption.toRight(NatsDecodeError(s"Expected int, got: $s")).map(RateLimited(_)))

  private given NatsCodec[Conflict] = new NatsCodec[Conflict]:
    def encode(e: Conflict): Chunk[Byte]                              = NatsCodec.stringCodec.encode(e.detail)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, Conflict] =
      NatsCodec.stringCodec.decode(bytes).map(Conflict(_))

  private given NatsCodec[IllegalArgumentException] = new NatsCodec[IllegalArgumentException]:
    def encode(e: IllegalArgumentException): Chunk[Byte] =
      NatsCodec.stringCodec.encode(e.getMessage)
    def decode(bytes: Chunk[Byte]): Either[NatsDecodeError, IllegalArgumentException] =
      NatsCodec.stringCodec.decode(bytes).map(IllegalArgumentException(_))

  /**
   * Readiness probe: blocks until the named service responds to a ping or 2 s
   * elapses. Replaces `ZIO.sleep` for service startup synchronization.
   */
  private def awaitService(name: String): ZIO[Nats, Nothing, Unit] =
    ServiceDiscovery
      .make(maxWait = 2.seconds, maxResults = 1)
      .flatMap(_.ping(name))
      .orDie
      .unit

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
          _     <- awaitService("echo-svc")
          reply <- nats.request[String, String](Subject("echo"), "hello", 5.seconds)
        } yield assertTrue(reply.value == "hello")
      }
    },

    test("environment-aware endpoint handler can require services") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("env-echo").in[String].out[String]
          _    <- nats
                 .service[Prefix](
                   ServiceConfig("env-echo-svc", "1.0.0"),
                   ep.handleZIO[Prefix](value => ZIO.serviceWith[Prefix](prefix => s"${prefix.value}:$value"))
                 )
                 .provideSomeLayer[Scope](ZLayer.succeed(Prefix("from-env")))
          _     <- awaitService("env-echo-svc")
          reply <- nats.request[String, String](Subject("env-echo"), "hello", 5.seconds)
        } yield assertTrue(reply.value == "from-env:hello")
      }
    },

    test("service registration combines environments from multiple endpoints") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          left  = ServiceEndpoint("env-left").in[String].out[String]
          right = ServiceEndpoint("env-right").in[String].out[String]
          _    <- nats
                 .service(
                   ServiceConfig("env-combined-svc", "1.0.0"),
                   left.handleZIO[Prefix](value => ZIO.serviceWith[Prefix](prefix => s"${prefix.value}:$value")),
                   right.handleZIO[Suffix](value => ZIO.serviceWith[Suffix](suffix => s"$value:${suffix.value}"))
                 )
                 .provideSomeLayer[Scope](ZLayer.succeed(Prefix("left")) ++ ZLayer.succeed(Suffix("right")))
          _  <- awaitService("env-combined-svc")
          r1 <- nats.request[String, String](Subject("env-left"), "value", 5.seconds)
          r2 <- nats.request[String, String](Subject("env-right"), "value", 5.seconds)
        } yield assertTrue(r1.value == "left:value", r2.value == "value:right")
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
          _  <- awaitService("multi-svc")
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
          _  <- awaitService("sort-svc")
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
          _      <- awaitService("typed-ok-svc")
          result <- nats
                      .requestService(ep, "hello", 5.seconds)
                      .mapError(e => new RuntimeException(e.toString))
        } yield assertTrue(result == "HELLO")
      }
    },

    test("requestService sends headers visible in ServiceRequest.headers") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("headers-echo").in[String].out[String]
          _    <- nats.service(
                 ServiceConfig("headers-echo-svc", "1.0.0"),
                 ep.handleWith { req =>
                   ZIO.succeed(req.headers.get("X-Version").headOption.getOrElse("missing"))
                 }
               )
          _      <- awaitService("headers-echo-svc")
          result <- nats
                      .requestService(
                        ep,
                        "ignored",
                        5.seconds,
                        PublishParams(headers = Headers("X-Version" -> "42"))
                      )
                      .mapError(e => new RuntimeException(e.toString))
        } yield assertTrue(result == "42")
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
          _      <- awaitService("typed-fail-svc")
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
          _ <- awaitService("raw-fail-svc")
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
          _      <- awaitService("ops-svc")
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
          _     <- awaitService("clock-svc")
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
          _     <- awaitService("inspect-svc")
          reply <- nats.request[String, String](Subject("inspect"), "test", 5.seconds)
        } yield assertTrue(
          reply.value.contains("subj=inspect"),
          reply.value.contains("payload=test")
        )
      }
    },

    test("handleWithZIO provides request metadata and required environment") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("inspect-env").in[String].out[String]
          _    <- nats
                 .service[Prefix](
                   ServiceConfig("inspect-env-svc", "1.0.0"),
                   ep.handleWithZIO[Prefix] { req =>
                     val traceId = req.headers.get("X-Trace-Id").headOption.getOrElse("none")
                     ZIO.serviceWith[Prefix] { prefix =>
                       s"${prefix.value} subj=${req.subject.value} trace=$traceId payload=${req.value}"
                     }
                   }
                 )
                 .provideSomeLayer[Scope](ZLayer.succeed(Prefix("env")))
          _      <- awaitService("inspect-env-svc")
          result <- nats
                      .requestService(
                        ep,
                        "test",
                        5.seconds,
                        PublishParams(headers = Headers("X-Trace-Id" -> "trace-123"))
                      )
                      .mapError(e => new RuntimeException(e.toString))
        } yield assertTrue(
          result.contains("env"),
          result.contains("subj=inspect-env"),
          result.contains("trace=trace-123"),
          result.contains("payload=test")
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
          _ <- awaitService("stats-svc")
          _ <- nats.request[String, String](Subject("counted"), "a", 5.seconds)
          _ <- nats.request[String, String](Subject("counted"), "b", 5.seconds)
          s <- svc.stats
                 .repeatUntil(_.endpoints.exists(e => e.name == "counted" && e.numRequests >= 2))
                 .timeout(5.seconds)
                 .someOrFail(new RuntimeException("stats never reached 2 requests"))
                 .orDie
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
          _ <- awaitService("reset-svc")
          _ <- nats.request[String, String](Subject("resetme"), "x", 5.seconds)
          // Wait for stats to reflect the request before resetting
          _ <- svc.stats
                 .repeatUntil(_.endpoints.exists(e => e.name == "resetme" && e.numRequests >= 1))
                 .timeout(5.seconds)
                 .someOrFail(new RuntimeException("stats never updated before reset"))
                 .orDie
          _ <- svc.reset
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
          // discovery.info blocks for maxWait — no explicit readiness probe needed
          discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
          responses <- discovery.info("info-svc")
        } yield assertTrue(
          responses.nonEmpty,
          responses.head.description.contains("test service"),
          responses.head.endpoints.map(_.name).toSet.intersect(Set("ep-a", "ep-b")).nonEmpty
        )
      }
    },

    test("requestService routes chained union errors to the correct member codec") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("union2-err")
                 .in[String]
                 .out[String]
                 .failsWith[NotFound]
                 .failsWith[Forbidden]
          _ <- nats.service(
                 ServiceConfig("union2-err-svc", "1.0.0"),
                 ep.handle {
                   case "notfound"  => ZIO.fail(NotFound("item-42"))
                   case "forbidden" => ZIO.fail(Forbidden("access denied"))
                   case s           => ZIO.succeed(s"ok:$s")
                 }
               )
          _  <- awaitService("union2-err-svc")
          r1 <- nats.requestService(ep, "notfound", 5.seconds).either
          r2 <- nats.requestService(ep, "forbidden", 5.seconds).either
          r3 <- nats.requestService(ep, "hello", 5.seconds).either
        } yield assertTrue(
          r1 == Left(NotFound("item-42")),
          r2 == Left(Forbidden("access denied")),
          r3 == Right("ok:hello")
        )
      }
    },

    test("requestService routes more than five chained union errors to the correct member codec") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("union6-err")
                 .in[String]
                 .out[String]
                 .failsWith[NotFound]
                 .failsWith[Forbidden]
                 .failsWith[ServiceUnavailable]
                 .failsWith[RateLimited]
                 .failsWith[Conflict]
                 .failsWith[IllegalArgumentException]
          _ <- nats.service(
                 ServiceConfig("union6-err-svc", "1.0.0"),
                 ep.handle {
                   case "notfound"    => ZIO.fail(NotFound("item-42"))
                   case "forbidden"   => ZIO.fail(Forbidden("access denied"))
                   case "unavailable" => ZIO.fail(ServiceUnavailable("down for maintenance"))
                   case "ratelimited" => ZIO.fail(RateLimited(60))
                   case "conflict"    => ZIO.fail(Conflict("duplicate key"))
                   case "illegal"     => ZIO.fail(IllegalArgumentException("bad input"))
                   case s             => ZIO.succeed(s"ok:$s")
                 }
               )
          _  <- awaitService("union6-err-svc")
          r1 <- nats.requestService(ep, "notfound", 5.seconds).either
          r2 <- nats.requestService(ep, "forbidden", 5.seconds).either
          r3 <- nats.requestService(ep, "unavailable", 5.seconds).either
          r4 <- nats.requestService(ep, "ratelimited", 5.seconds).either
          r5 <- nats.requestService(ep, "conflict", 5.seconds).either
          r6 <- nats.requestService(ep, "illegal", 5.seconds).either
          r7 <- nats.requestService(ep, "hello", 5.seconds).either
        } yield assertTrue(
          r1 == Left(NotFound("item-42")),
          r2 == Left(Forbidden("access denied")),
          r3 == Left(ServiceUnavailable("down for maintenance")),
          r4 == Left(RateLimited(60)),
          r5 == Left(Conflict("duplicate key")),
          r6.left.exists {
            case e: IllegalArgumentException => e.getMessage == "bad input"
            case _                           => false
          },
          r7 == Right("ok:hello")
        )
      }
    },

    test("requestService allows repeated chained failsWith calls for the same error type") {
      ZIO.scoped {
        for {
          nats <- ZIO.service[Nats]
          ep    = ServiceEndpoint("repeat-err")
                 .in[String]
                 .out[String]
                 .failsWith[NotFound]
                 .failsWith[NotFound]
          _ <- nats.service(
                 ServiceConfig("repeat-err-svc", "1.0.0"),
                 ep.handle {
                   case "notfound" => ZIO.fail(NotFound("item-42"))
                   case s          => ZIO.succeed(s"ok:$s")
                 }
               )
          _  <- awaitService("repeat-err-svc")
          r1 <- nats.requestService(ep, "notfound", 5.seconds).either
          r2 <- nats.requestService(ep, "hello", 5.seconds).either
        } yield assertTrue(
          r1 == Left(NotFound("item-42")),
          r2 == Right("ok:hello")
        )
      }
    },

    test("ServiceErrorMapper[NatsError] uses e.message not e.toString") {
      val mapper      = summon[ServiceErrorMapper[NatsError]]
      val (msg, code) = mapper.toErrorResponse(NatsError.Timeout("db-unavailable"))
      assertTrue(msg == "db-unavailable", code == 500)
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
          _         <- awaitService("disc-stats-svc")
          _         <- nats.request[String, String](Subject("stat-ep"), "ping", 5.seconds)
          discovery <- ServiceDiscovery.make(maxWait = 3.seconds)
          responses <- discovery
                         .stats("disc-stats-svc")
                         .repeatUntil(_.flatMap(_.endpoints).exists(_.numRequests >= 1))
                         .timeout(5.seconds)
                         .someOrFail(new RuntimeException("discovery stats never updated"))
                         .orDie
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
