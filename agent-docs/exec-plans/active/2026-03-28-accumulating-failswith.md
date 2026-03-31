# Accumulating failsWith Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `ServiceEndpoint.failsWith[E]` accumulate error union members across chained calls so endpoints can support any number of typed error variants.

**Architecture:** Replace the fixed-arity union builder API with a single additive `failsWith[E]` implementation. Compose the existing `TypedErrorCodec[Err]` and `ServiceErrorMapper[Err]` captured on the endpoint with one new `ErrorCodecPart[E]` and `ServiceErrorMapper[E]` per chained call so encode/decode and mapper dispatch continue to work for arbitrarily large unions.

**Tech Stack:** Scala 3, ZIO, sbt, NATS service framework.

---

### Task 1: Write the failing tests first

**Files:**
- Modify: `zio-nats-test/src/test/scala/zio/nats/ServiceSpec.scala`

**Step 1: Write failing tests**

- Replace fixed-arity `failsWith[A, B, ...]` call sites with chained `.failsWith[A].failsWith[B]` calls.
- Add a runtime test covering more than five chained error types.
- Add a runtime test covering repeated `.failsWith[SameType]` calls.

**Step 2: Run test compile to verify it fails**

Run: `/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "zioNatsTest/Test/compile"`
Expected: compile failure showing chained `failsWith` currently narrows to the last error type instead of accumulating the union.

### Task 2: Implement accumulating typed-error composition

**Files:**
- Modify: `zio-nats-core/src/main/scala/zio/nats/NatsCodec.scala`
- Modify: `zio-nats-core/src/main/scala/zio/nats/service/ServiceEndpoint.scala`

**Step 1: Add codec composition helper**

- Introduce a `TypedErrorCodec.append` helper that merges `tagRoutes`, checks the newly-added error member first during encode, and otherwise delegates to the previously captured codec.

**Step 2: Update `ServiceEndpoint.failsWith[E]`**

- Remove the fixed-arity overloads.
- Make the single public `failsWith[E]` append `E` onto the existing error channel `Err`, returning `ServiceEndpoint[In, Err | E, Out]`.
- Compose `ServiceErrorMapper` the same way: try `E` first, otherwise delegate to the existing `Err` mapper.

### Task 3: Update docs and examples

**Files:**
- Modify: `zio-nats-core/src/main/scala/zio/nats/Nats.scala`
- Modify: `examples/src/main/scala/ServiceApp.scala`
- Modify: `docs/guides/04-service.md`
- Modify: `CLAUDE.md`

**Step 1: Refresh API examples**

- Change examples and guide snippets to chained `.failsWith[A].failsWith[B]` usage.
- Update architecture notes to describe accumulating chaining rather than fixed arity.

### Task 4: Verify compile and integration behavior

**Files:**
- No new files.

**Step 1: Compile everything**

Run: `/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat compile`
Expected: all subprojects compile.

**Step 2: Run service integration tests**

Run: `/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "zioNatsTest/testOnly zio.nats.ServiceSpec"`
Expected: service tests pass, including chained >5 and repeated `failsWith` cases.

**Step 3: Run full test suite**

Run: `/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "zioNatsTest/test"`
Expected: all tests pass.
