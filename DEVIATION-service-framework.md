# Deviations from PLAN-service-framework.md

This file documents differences between the implementation and the specification in `PLAN-service-framework.md`.

---

## 1. jnats API Differences

### `StatsResponse.getEndpointStats()` → `getEndpointStatsList()`

**Plan assumed:** `j.getEndpointStats()` on `io.nats.service.StatsResponse`

**Actual jnats 2.25.2:** The method is named `getEndpointStatsList()`.

**Fix applied:** `ServiceModels.scala` uses `j.getEndpointStatsList()`.

---

### `EndpointInfo` class does not exist

**Plan assumed:** `io.nats.service.EndpointInfo` exists and `InfoResponse.getEndpoints()` returns `List<EndpointInfo>`.

**Actual jnats 2.25.2:** There is no `EndpointInfo` class. `InfoResponse.getEndpoints()` returns `List<Endpoint>` where `Endpoint` is `io.nats.service.Endpoint`.

**Fix applied:** `ServiceModels.scala` imports `Endpoint => JEndpoint` and `EndpointInfo.fromJava` accepts `JEndpoint`.

---

### `NatsCodec.encode()` returns `Chunk[Byte]`, not `Either`

**Plan assumed:** `outCodec.encode(out)` returns an `Either` and can be wrapped with `ZIO.fromEither`.

**Actual:** `NatsCodec.encode` returns `Chunk[Byte]` directly (documented as "must not throw").

**Fix applied:** `ServiceEndpoint.scala` uses `ZIO.attempt(outCodec.encode(out))` instead of `ZIO.fromEither(outCodec.encode(out))`.

---

### `Service.stop()` is `void`, not `CompletableFuture`

**Plan assumed:** `svc.stop()` returns a `CompletableFuture<Boolean>`.

**Actual jnats 2.25.2:** `Service.stop()` is `void`. (`startService()` returns `CompletableFuture<Boolean>`, but that completes when the service *stops*, not when it starts.)

**Fix applied:** `Nats.scala` uses `ZIO.attempt(svc.stop()).ignoreLogged` which is correct for a void method.

---

### `NatsService.description` uses `getInfoResponse().getDescription()`

**Plan assumed:** `jService.getDescription()` is directly available on `io.nats.service.Service`.

**Actual jnats 2.25.2:** `Service.getDescription()` exists (confirmed via source inspection). The implementation uses it directly. No deviation here in the final code.

---

### `Discovery` constructor takes `long maxTimeMillis`, not `java.time.Duration`

**Plan assumed:** `new JDiscovery(nats.underlying, maxWait.asJava, maxResults)` (using `java.time.Duration`).

**Actual jnats 2.25.2:** The `Discovery(Connection, long, int)` constructor takes `maxTimeMillis` as `long` milliseconds.

**Fix applied:** `ServiceDiscovery.scala` uses `maxWait.toMillis` (long) instead of `maxWait.asJava` (java.time.Duration).

---

### `NatsServiceLive.endpointStats` uses stats filtering

**Plan assumed:** A direct `jService.getEndpointStats(endpointName)` method exists on `Service`.

**Actual jnats 2.25.2:** No such single-endpoint accessor exists. Stats are only available via `getStatsResponse()` which includes all endpoints.

**Fix applied:** `NatsService.scala` implements `endpointStats` by calling `getStatsResponse()` and filtering by name.

---

## 2. `buildGroup` Implementation

**Plan provided:**
```scala
private def buildGroup(g: ServiceGroup): JGroup =
  val jGroup = new JGroup(g.name)
  g.parent.foreach(p => buildGroup(p).appendGroup(jGroup))
  g.parent.map(buildGroup).getOrElse(jGroup)
```

This implementation has a bug: it calls `buildGroup(parent)` twice (once in `foreach`, once in `map`), and the second call creates a fresh parent without the appended child, so the result discards the append.

**Fix applied:** `ServiceEndpoint.scala` uses a corrected implementation:
```scala
private def buildGroup(g: ServiceGroup): JGroup =
  g.parent match
    case None =>
      new JGroup(g.name)
    case Some(parent) =>
      val jParent = buildGroup(parent)
      val jSelf   = new JGroup(g.name)
      jParent.appendGroup(jSelf)
      jParent
```

This walks the chain once, appends the child to the parent, and returns the root group — which is what `ServiceEndpoint.builder().group(...)` expects.

---

## 3. Scala 3 Type Inference Ambiguity for Infallible Handlers

**Plan assumed:** Handlers returning `IO[Nothing, Out]` (infallible) would automatically select `ServiceErrorMapper[Nothing]` without any annotation.

**Actual Scala 3.3.7 behaviour:** When multiple `ServiceErrorMapper` instances are in scope (for `NatsError`, `String`, and `Nothing`), the compiler fails to infer the `Err` type parameter for handlers returning `ZIO.succeed(...)` or similar infallible effects. The error is:

```
Ambiguous given instances: both given_ServiceErrorMapper_NatsError and
given_ServiceErrorMapper_String match type ServiceErrorMapper[Err]
```

This is a known Scala 3 limitation: when bidirectional type checking resolves a context bound, multiple candidate givens can become ambiguous even if the "correct" one would be `Nothing`.

**Fix applied:** Infallible handler calls must explicitly specify `[Nothing]`:

```scala
// ❌ Plan assumed this would compile:
ep.implement(value => ZIO.succeed(value))

// ✅ Required in actual Scala 3:
ep.implement[Nothing](value => ZIO.succeed(value))
```

All tests and examples have been updated to use explicit `[Nothing]` for infallible handlers. Handlers with a concrete error type (e.g. `IO[MyError, Out]`) work without annotation because only one given matches.

**User impact:** Minimal — adding `[Nothing]` is a one-word annotation. For handlers with a concrete error type, no change is needed.

---

## 4. `ServiceEndpoint` has no `val ServiceEndpoint` companion in `package.scala`

**Note:** `ServiceEndpoint` is a `case class` with a companion object. Re-exporting it in `package.scala` as:
```scala
type ServiceEndpoint[In, Out] = service.ServiceEndpoint[In, Out]
val ServiceEndpoint = service.ServiceEndpoint
```
works correctly since the companion is re-exported alongside the type alias.

---

## Summary of Files That Differ from the Plan

| Deviation | File | Nature |
|---|---|---|
| `getEndpointStatsList` | `ServiceModels.scala` | Wrong method name in plan |
| `EndpointInfo` → `Endpoint` | `ServiceModels.scala` | Non-existent class in plan |
| `encode` returns `Chunk`, not `Either` | `ServiceEndpoint.scala` | Wrong return type assumed in plan |
| `Service.stop()` is void | `Nats.scala` | Wrong return type assumed in plan |
| `Discovery` takes `long` ms | `ServiceDiscovery.scala` | Wrong constructor signature in plan |
| No `getEndpointStats(name)` | `NatsService.scala` | Non-existent method in plan |
| `buildGroup` fix | `ServiceEndpoint.scala` | Logic bug in plan |
| `[Nothing]` required for infallible handlers | `ServiceSpec.scala`, `ServiceApp.scala` | Scala 3 inference limitation |
