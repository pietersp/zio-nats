---
id: testing
title: Testing
---

`zio-nats-testkit` starts a real NATS server in a Docker container via [testcontainers](https://testcontainers.com) and provides a wired `Nats` layer pointing at it. There are no mocks to maintain and no manual server setup - your tests run against the same protocol your production code uses.

:::warning
Docker must be running on the test machine. See [Podman / WSL](#podman--wsl) if you use Podman instead.
:::

## Installation

Add the testkit to your test dependencies:

```scala
libraryDependencies += "io.github.pietersp" %% "zio-nats-testkit" % "@VERSION@" % Test
```

## Writing your first test

`NatsTestLayers.nats` is a `ZLayer` that starts a NATS container, waits for it to be ready, and provides a `Nats` service connected to it. Use `.provideShared` to start the container once and share it across every test in the suite - container startup takes a few seconds and sharing it keeps the suite fast.

Because NATS subjects are global within the server (not scoped per-test), running tests concurrently risks one test's subscription receiving another test's messages. `@@ sequential` serialises execution. `@@ withLiveClock` is required because `ZIO.sleep` and timeouts need the live clock, not ZIO's virtual test clock:

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

`@@ timeout(60.seconds)` on the whole suite guards against a stuck container startup - if the suite takes longer than 60 seconds the test run fails rather than hanging indefinitely.

## JetStream, KV, and Object Store

The NATS container starts with JetStream enabled, so every API works without extra server configuration. Compose the layers you need on top of `NatsTestLayers.nats` using `>+>`:

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

    test("stores a message and returns a sequence number") {
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

The same pattern applies to KV and Object Store - add `KeyValue.live(bucketName)`, `KeyValueManagement.live`, `ObjectStore.live(bucketName)`, or `ObjectStoreManagement.live` to the layer chain as needed.

## Podman / WSL

The exact setup for Podman varies by OS, Podman version, and WSL configuration - treat the following as a starting point rather than a definitive recipe. If you use Podman instead of Docker, set these two environment variables before running tests:

```bash
export DOCKER_HOST=unix:///tmp/podman/podman-machine-default-api.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

`TESTCONTAINERS_RYUK_DISABLED=true` is required because Podman does not support the Ryuk container reaper that testcontainers uses by default for cleanup.

## Next steps

- [Modules reference](../reference/03-modules.md) - artifact coordinates for each integration
- [Error handling reference](../reference/02-error-handling.md) - assert on specific `NatsError` variants
