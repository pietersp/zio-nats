# Request Headers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `params: PublishParams = PublishParams.empty` to `Nats.request` and `Nats.requestService` so callers can attach headers to outgoing requests, symmetrically with `publish`.

**Architecture:** The `Nats` trait gains a `params` parameter (with a default) on both request methods. `NatsLive` branches on `params.headers.nonEmpty`: empty-headers calls keep the existing `conn.request(subject, bytes, timeout)` fast path; non-empty calls build a `NatsMessage` and call `conn.request(msg, timeout)`. Server-side header propagation to `ServiceRequest.headers` already works — no server-side changes needed.

**Tech Stack:** Scala 3, ZIO 2, jnats (`io.nats.client.Connection`), zio-test (ZIOSpecDefault), testcontainers NATS (via `NatsTestLayers`).

---

### Task 1: Write the failing tests

**Files:**
- Modify: `zio-nats-test/src/test/scala/zio/nats/NatsPubSubSpec.scala`
- Modify: `zio-nats-test/src/test/scala/zio/nats/ServiceSpec.scala`

Add the two tests below. They will **fail to compile** until Task 2 adds the `params` parameter — that is the expected red-bar state.

- [ ] **Step 1: Add the `request`-with-headers test to `NatsPubSubSpec.scala`**

Find the existing `"reply-to is set and subscriber can use it to reply"` test (around line 68) and add this test immediately after it in the same suite:

```scala
test("request sends headers that the subscriber receives") {
  val subject = Subject("test.request.with-headers")
  for {
    nats         <- ZIO.service[Nats]
    headersLatch <- Promise.make[Nothing, Headers]
    fiber        <- nats
                      .subscribe[Chunk[Byte]](subject)
                      .tap { env =>
                        val capture = headersLatch.succeed(env.message.headers)
                        env.message.replyTo match {
                          case Some(reply) =>
                            capture *> nats.publish(reply, Chunk.fromArray("pong".getBytes))
                          case None => capture.unit
                        }
                      }
                      .take(1)
                      .runDrain
                      .fork
    _            <- nats.flush(5.seconds)
    _            <- nats.request[Chunk[Byte], Chunk[Byte]](
                      subject,
                      Chunk.fromArray("ping".getBytes),
                      5.seconds,
                      PublishParams(headers = Headers("X-Version" -> "2"))
                    )
    received     <- headersLatch.await
    _            <- fiber.interrupt
  } yield assertTrue(received.get("X-Version") == Chunk("2"))
},
```

- [ ] **Step 2: Add the `requestService`-with-headers test to `ServiceSpec.scala`**

Add this test to the `"Service Framework"` suite (e.g. immediately after `"requestService returns typed reply on success"`):

```scala
test("requestService sends headers visible in ServiceRequest.headers") {
  ZIO.scoped {
    for {
      nats <- ZIO.service[Nats]
      ep    = ServiceEndpoint("headers-echo").in[String].out[String]
      _    <- nats.service(
                ServiceConfig("headers-echo-svc", "1.0.0"),
                ep.handleWith { req =>
                  // Echo the X-Version header value back as the reply body
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
```

- [ ] **Step 3: Confirm both tests fail to compile**

```
sbt zioNatsTest/Test/compile
```

Expected: compilation error — `request` and `requestService` do not accept a fourth `params` argument.

---

### Task 2: Extend the `Nats` trait and implement in `NatsLive`

**Files:**
- Modify: `zio-nats-core/src/main/scala/zio/nats/Nats.scala`

All changes are in a single file. The trait gains default-parameter overloads; `NatsLive` gets the branching logic.

- [ ] **Step 1: Update the `request` signature in the `Nats` trait**

In the `Nats` trait, replace:

```scala
def request[A: NatsCodec, B: NatsCodec](
  subject: Subject,
  request: A,
  timeout: Duration
): IO[NatsError, Envelope[B]]
```

with:

```scala
def request[A: NatsCodec, B: NatsCodec](
  subject: Subject,
  request: A,
  timeout: Duration,
  params: PublishParams = PublishParams.empty
): IO[NatsError, Envelope[B]]
```

- [ ] **Step 2: Update the `requestService` signature in the `Nats` trait**

In the `Nats` trait, replace:

```scala
def requestService[In, Err, Out](
  endpoint: ServiceEndpointDescriptor[In, Err, Out],
  input: In,
  timeout: Duration
): IO[NatsError | Err, Out]
```

with:

```scala
def requestService[In, Err, Out](
  endpoint: ServiceEndpointDescriptor[In, Err, Out],
  input: In,
  timeout: Duration,
  params: PublishParams = PublishParams.empty
): IO[NatsError | Err, Out]
```

- [ ] **Step 3: Update `NatsLive.request`**

Replace the entire `override def request` method in `NatsLive` with:

```scala
override def request[A: NatsCodec, B: NatsCodec](
  subject: Subject,
  request: A,
  timeout: Duration,
  params: PublishParams
): IO[NatsError, Envelope[B]] =
  ZIO
    .attempt(NatsCodec[A].encode(request))
    .mapError(e =>
      NatsError.SerializationError(s"Failed to encode request for subject '${subject.value}': ${e.toString}", e)
    )
    .flatMap { bytes =>
      val send =
        if (params.headers.isEmpty)
          ZIO.attemptBlocking(Option(conn.request(subject.value, bytes.toArray, timeout.asJava)))
        else {
          val msg = NatsMessage.toJava(subject.value, bytes, headers = params.headers)
          ZIO.attemptBlocking(Option(conn.request(msg, timeout.asJava)))
        }
      send
        .mapError(NatsError.fromThrowable)
        .flatMap {
          case None      =>
            ZIO.fail(NatsError.Timeout(s"No reply received for subject '${subject.value}' within $timeout"))
          case Some(jMsg) =>
            val msg = NatsMessage.fromJava(jMsg)
            extractServiceError(msg) match {
              case Some((errMsg, code)) => ZIO.fail(NatsError.ServiceCallFailed(errMsg, code))
              case None                 =>
                ZIO
                  .fromEither(msg.decode[B])
                  .mapBoth(e => NatsError.DecodingError(e.message, e), Envelope(_, msg))
            }
        }
    }
```

- [ ] **Step 4: Update `NatsLive.requestService`**

Replace the entire `override def requestService` method in `NatsLive` with:

```scala
override def requestService[In, Err, Out](
  endpoint: ServiceEndpointDescriptor[In, Err, Out],
  input: In,
  timeout: Duration,
  params: PublishParams
): IO[NatsError | Err, Out] =
  ZIO
    .attempt(endpoint.inCodec.encode(input))
    .mapError(e =>
      NatsError.SerializationError(
        s"Failed to encode request for subject '${endpoint.effectiveSubject.value}': ${e.toString}",
        e
      )
    )
    .flatMap { bytes =>
      val send =
        if (params.headers.isEmpty)
          ZIO.attemptBlocking(
            Option(conn.request(endpoint.effectiveSubject.value, bytes.toArray, timeout.asJava))
          )
        else {
          val msg = NatsMessage.toJava(endpoint.effectiveSubject.value, bytes, headers = params.headers)
          ZIO.attemptBlocking(Option(conn.request(msg, timeout.asJava)))
        }
      send
        .mapError(NatsError.fromThrowable)
        .flatMap {
          case None       =>
            ZIO.fail(
              NatsError.Timeout(
                s"No reply received for subject '${endpoint.effectiveSubject.value}' within $timeout"
              )
            )
          case Some(jMsg) =>
            val msg = NatsMessage.fromJava(jMsg)
            decodeServiceReply(msg, endpoint.errCodec, endpoint.outCodec)
        }
    }
```

- [ ] **Step 5: Compile to verify no errors**

```
sbt zioNatsTest/Test/compile
```

Expected: clean compile.

- [ ] **Step 6: Run the two new tests to confirm they pass**

```
sbt "zioNatsTest/testOnly zio.nats.NatsPubSubSpec -- -t request sends headers"
sbt "zioNatsTest/testOnly zio.nats.ServiceSpec -- -t requestService sends headers"
```

Expected: both pass.

- [ ] **Step 7: Run the full test suite to confirm no regressions**

```
sbt zioNatsTest/test
```

Expected: all tests pass.

---

### Task 3: Update ScalaDocs

**Files:**
- Modify: `zio-nats-core/src/main/scala/zio/nats/Nats.scala`

Update the ScalaDoc blocks on both methods in the `Nats` trait to document the `params` parameter.

- [ ] **Step 1: Update ScalaDoc for `request`**

In the `Nats` trait, update the ScalaDoc above `request` to add a `@param params` line. Insert it before the existing `@param`-less description (after the last sentence in the body):

```scala
/**
 * Encode `request` as `A`, send it, await the reply, then decode it as `B`.
 *
 * Returns an [[Envelope]] containing both the decoded response and the raw
 * [[NatsMessage]] (so headers and other metadata remain accessible).
 *
 * Pass `Chunk[Byte]` for `A` and/or `B` to use the identity codec (raw bytes).
 *
 * Fails with [[NatsError.DecodingError]] if the reply cannot be decoded as `B`.
 * Fails with [[NatsError.Timeout]] if no reply is received within `timeout`.
 * Fails with [[NatsError.ServiceCallFailed]] if the responder is a NATS Micro
 * service endpoint that sent an error response (detected via the standard
 * `Nats-Service-Error` / `Nats-Service-Error-Code` headers).
 *
 * @param params
 *   Optional [[PublishParams]] for headers (defaults to [[PublishParams.empty]]).
 *   The `replyTo` field of `params` is ignored — NATS manages the reply inbox
 *   automatically for request/reply.
 */
```

- [ ] **Step 2: Update ScalaDoc for `requestService`**

In the `requestService` ScalaDoc, add the `params` description after the `timeout` description at the end of the doc. Add this line to the existing parameter list area (there are no `@param` annotations currently — add it at the end of the description body):

```
 * @param params
 *   Optional [[PublishParams]] for headers (defaults to [[PublishParams.empty]]).
 *   Headers are visible to the server handler via [[service.ServiceRequest#headers]].
 *   The `replyTo` field of `params` is ignored.
```

- [ ] **Step 3: Format and compile**

```
sbt scalafmtAll
sbt zioNatsTest/Test/compile
```

Expected: clean compile, no format diff.

- [ ] **Step 4: Commit**

```bash
git add zio-nats-core/src/main/scala/zio/nats/Nats.scala \
        zio-nats-test/src/test/scala/zio/nats/NatsPubSubSpec.scala \
        zio-nats-test/src/test/scala/zio/nats/ServiceSpec.scala
git commit -m "$(cat <<'EOF'
feat: add params to request and requestService for header support

Extends Nats.request and Nats.requestService with an optional
PublishParams parameter (default PublishParams.empty), making
request/reply symmetric with publish. Headers are forwarded on the
wire and arrive in ServiceRequest.headers on the server side.
All existing call sites are source-compatible.
EOF
)"
```
