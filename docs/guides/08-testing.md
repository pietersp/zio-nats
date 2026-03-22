---
id: testing
title: Testing
---

# Testing

> Integration tests against a real NATS server — no mocks required.

`zio-nats-testkit` starts a NATS container via [testcontainers](https://testcontainers.com)
and provides a `Nats` layer wired to it. Your tests run against a real server with no manual
setup.

## Prerequisites

- Docker (or Podman — see note below)
- `zio-test` in your test dependencies

## Installation

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "@VERSION@" % Test
```

## Basic test

```scala
import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.nats.*
import zio.nats.testkit.NatsTestLayers

object PublishSubscribeSpec extends ZIOSpecDefault {
  def spec = suite("PublishSubscribeSpec")(

    test("publishes and receives a message") {
      for {
        nats  <- ZIO.service[Nats]
        fiber <- nats.subscribe[String](Subject("t")).take(1).runCollect.fork
        _     <- ZIO.sleep(200.millis)
        _     <- nats.publish(Subject("t"), "hello")
        msgs  <- fiber.join
      } yield assertTrue(msgs.head.value == "hello")
    }

  ).provideShared(NatsTestLayers.nats) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
```

**What's happening:**

1. `NatsTestLayers.nats` — a `ZLayer` that starts a NATS container (once per suite, shared across all tests) and provides a `Nats` service connected to it.
2. `.provideShared(...)` — Docusaurus uses the same container instance for every test in the suite. Starting a container takes a few seconds; sharing it keeps the suite fast.
3. `@@ sequential` — runs tests one at a time. NATS subjects are not namespaced per-test, so concurrent tests can interfere with each other's subscriptions.
4. `@@ withLiveClock` — required because `ZIO.sleep` and timeouts inside tests need the live clock, not ZIO's test clock.
5. `@@ timeout(60.seconds)` — fails the suite if it takes longer than 60 seconds, guarding against a stuck container startup.

## JetStream, KV, and Object Store

The testcontainer is started with `--js` (JetStream enabled), so all APIs work without any
extra configuration:

```scala
import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.nats.*
import zio.nats.jetstream.*
import zio.nats.kv.*
import zio.nats.testkit.NatsTestLayers

object JetStreamSpec extends ZIOSpecDefault {
  def spec = suite("JetStreamSpec")(

    test("publishes to a stream") {
      for {
        jsm <- ZIO.service[JetStreamManagement]
        js  <- ZIO.service[JetStream]
        _   <- jsm.addStream(StreamConfig(name = "TEST", subjects = List("test.>")))
        ack <- js.publish(Subject("test.one"), "payload")
      } yield assertTrue(ack.seqno == 1L)
    }

  ).provideShared(
    NatsTestLayers.nats >+> JetStream.live >+> JetStreamManagement.live
  ) @@ sequential @@ withLiveClock @@ timeout(60.seconds)
}
```

Add whatever layers your tests need on top of `NatsTestLayers.nats` using `>+>`.

## Podman / WSL

If you use Podman instead of Docker, set these environment variables before running tests:

```bash
export DOCKER_HOST=unix:///tmp/podman/podman-machine-default-api.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

`TESTCONTAINERS_RYUK_DISABLED=true` is required because Podman does not support the Ryuk
container reaper that testcontainers uses by default.

## Next steps

- [Pub/Sub guide](./01-pubsub) — what to test
- [Error handling reference](../reference/02-error-handling) — assert on specific `NatsError` variants
- [Modules reference](../reference/03-modules) — all available artifacts and their scopes
