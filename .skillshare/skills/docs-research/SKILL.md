---
name: docs-research
description: Research zio-nats source code systematically before writing or updating documentation.
---

# Source Code Research for Documentation

This procedure guides systematic investigation of a topic before writing documentation. Complete all phases before writing a word of prose.

## Phase 1a: Core Source Files

Locate the central service or type using Glob and Grep:

```text
Glob: **/src/main/scala/zio/nats/<TypeName>.scala
Glob: **/src/main/scala/zio/nats/**/<TypeName>.scala
```

For each file found:
- Read the complete source to understand public methods, companion constructors, and `ZLayer` factories
- Check the Key Files table in `CLAUDE.md` — the primary source files are listed there with descriptions
- Review existing documentation in `docs/guides/` and `docs/reference/`

**Primary source locations (from CLAUDE.md):**

| Area                | File |
|---------------------|------|
| Core pub/sub        | `zio-nats-core/src/main/scala/zio/nats/Nats.scala` |
| Serialization       | `zio-nats-core/src/main/scala/zio/nats/NatsCodec.scala` |
| JetStream service   | `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStream.scala` |
| JetStream mgmt      | `zio-nats-core/src/main/scala/zio/nats/jetstream/JetStreamManagement.scala` |
| Consumer            | `zio-nats-core/src/main/scala/zio/nats/jetstream/Consumer.scala` |
| Key-Value           | `zio-nats-core/src/main/scala/zio/nats/kv/KeyValue.scala` |
| Object Store        | `zio-nats-core/src/main/scala/zio/nats/objectstore/ObjectStore.scala` |
| Service framework   | `zio-nats-core/src/main/scala/zio/nats/service/NatsService.scala` |
| Config              | `zio-nats-core/src/main/scala/zio/nats/config/NatsConfig.scala` |
| Error model         | `zio-nats-core/src/main/scala/zio/nats/NatsError.scala` |
| Core types          | `zio-nats-core/src/main/scala/zio/nats/NatsCoreTypes.scala` |
| Models              | `zio-nats-core/src/main/scala/zio/nats/NatsModels.scala` |
| Package re-exports  | `zio-nats-core/src/main/scala/zio/nats/package.scala` |

## Phase 1b: Supporting Types

Map the dependency graph:
- Grep imports in test files to reveal related types
- Trace method return types — if a method returns `Envelope[A]`, understand `Envelope`
- Identify which `NatsError` subtypes a service can produce
- Check `package.scala` to see what is re-exported and therefore public API

## Phase 1c: Real-World Patterns

Find practical usage:
- Read examples in `examples/src/main/scala/` — these are the canonical runnable demos
- Read integration tests in `zio-nats-test/src/test/scala/` for idiomatic usage and edge cases
- Grep for the type/method name across all test and example files to find every usage pattern

## Phase 1d: Git History

Uncover design rationale:
```bash
git log --grep="<TypeName>" --oneline
git log --grep="<topic keyword>" --oneline
```

Look for:
- API design decisions and rationale
- Known caveats and non-obvious behaviours
- Bug fixes that reveal edge cases
- Breaking changes that explain current constraints

Run multiple queries using the service name, method names, and related keywords.

## Research Questions to Answer Before Writing

Before starting any guide or reference page, answer these questions:

1. Which services are involved, and what is their `ZLayer` dependency chain?
2. What `NatsError` subtypes can each method return?
3. Which `NatsCodec[A]` is required, and how is it resolved?
4. What is the minimal working example — the fewest lines to get a result?
5. What is a realistic production-like example — something a reader would actually build?
6. What are the most common mistakes or gotchas?
7. What is the nearest alternative API, and when should a reader choose this one vs. that?
8. What does the existing documentation already cover? Avoid duplicating it; link to it.
