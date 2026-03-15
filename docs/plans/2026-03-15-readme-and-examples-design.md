# Design: README and examples/ for zio-nats

Date: 2026-03-15

## Goal

Produce thorough, open-source-quality documentation and runnable examples for the `zio-nats` library, targeting Scala/ZIO developers who want to use it as a library dependency.

## Decisions

- **Single README.md** at project root (Option A, progressive disclosure)
- **`examples/` sbt module** at project root with two compilable `App` objects
- No separate `docs/` pages — the library scope doesn't warrant it yet

## README.md structure

1. Header + badges (Scala 2.13 / 3, ZIO 2, Apache-2.0)
2. Installation (`libraryDependencies` for both artifacts)
3. Quick start (~20 lines: connect, publish, subscribe, print 3 messages, exit)
4. Core concepts (service graph: Nats → JetStream / KV / ObjectStore via ZLayer)
5. Per-subsystem sections, each with 1 paragraph + minimal snippet:
   - Pub/Sub & Request-Reply
   - JetStream (publish + consume)
   - Key-Value store (put/get/CAS/watch)
   - Object Store
   - Connection Events
6. Error handling (NatsError ADT, pattern match example)
7. Testing (NatsTestLayers.nats, ZIOSpecDefault wiring, Podman note)
8. NatsConfig reference table (all fields + defaults)
9. Link to `examples/` directory

## examples/ module

### examples/src/main/scala/QuickStartApp.scala
Mirrors the README quick-start:
- Connect via `Nats.default`
- Subscribe in a background fiber
- Publish 3 messages
- Collect received messages
- Exit cleanly

### examples/src/main/scala/RealisticApp.scala
A more complete scenario (~80-100 lines):
- Create a JetStream stream via `JetStreamManagement`
- Publish 5 messages via `JetStream`
- Consume them as a `ZStream`, ack each one
- Use a KV bucket to track processed count
- Log connection events via `NatsConnectionEvents`
- Graceful shutdown

### build.sbt additions
New `zioNatsExamples` sub-project:
- `dependsOn(zioNats)`
- `publish / skip := true`
- Requires a locally running NATS server with JetStream (`nats-server -js`)

## Files to create/modify

| File | Action |
|------|--------|
| `README.md` | Create |
| `examples/src/main/scala/QuickStartApp.scala` | Create |
| `examples/src/main/scala/RealisticApp.scala` | Create |
| `build.sbt` | Edit (add `zioNatsExamples` project, add to `root.aggregate`) |
