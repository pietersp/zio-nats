---
name: docs-how-to-guide
description: Write task-oriented how-to guides for zio-nats using the repo's documentation conventions.
---

# How-To Guide Writing for zio-nats

How-to guides are **goal-oriented**: the reader arrives with a specific task in mind and leaves having accomplished it. They assume familiarity with ZIO and NATS basics. Focus on the task — not on teaching concepts from scratch.

---

## Step 1: Deep Research

Run `/docs-research` before writing a single line of prose. Answer all research questions from that skill, plus these zio-nats-specific ones:

1. Which services are in scope (`Nats`, `JetStream`, `KeyValue`, `ObjectStore`, `NatsService`)?
2. What is the complete `ZLayer` dependency chain the reader must wire?
3. Which `NatsCodec[A]` instances are required, and how does the reader obtain them?
4. Which `NatsError` subtypes can the operations produce, and how should the reader handle them?
5. What is the simplest possible working example (fewest lines, real result)?
6. What is a realistic production-like example (the kind of code someone would actually ship)?
7. What are the most common mistakes for this specific task?
8. Is there an existing guide that covers a prerequisite? Link to it rather than re-explaining.
9. What does the reader need running locally? (NATS server, JetStream enabled, Docker?)

---

## Step 2: Design Structure

Use this skeleton — adapt section names to the specific task:

```text
## Prerequisites
## The Problem (or: Why This Matters)
## Setting Up
## <Core Task Steps — 2–4 sections, each one concept>
## Putting It Together
## Running the Example
## Next Steps
```

Rules:
- Each step section introduces one concept, shows code, and demonstrates the result.
- Never branch the narrative with "alternatively" or "if you need X, use Y instead" — pick the recommended path and note alternatives briefly at the end.
- Keep the scope tight. If a section is becoming a reference page, link out instead.

---

## Step 3: Write the Guide

**File placement:** `docs/guides/NN-kebab-name.md` where `NN` is the next available number.

**Frontmatter template:**
```yaml
---
id: NN-guide-name
title: "How to <accomplish specific task>"
sidebar_label: "<Short Label>"
---
```

**Opening paragraph:** Start with the motivating problem — what pain does this solve? Why would someone need this? One to three sentences, no fluff. Make the reader nod.

**Prerequisites section:** List what the reader needs before starting:
- zio-nats dependency (link to [modules reference](../reference/03-modules.md))
- Running NATS server (Docker one-liner if JetStream is needed: `docker run -p 4222:4222 nats -js`)
- Required imports: always `import zio.nats.*`
- Any codec dependency (`zio-nats-zio-blocks`, `zio-nats-jsoniter`, etc.)

**Layer wiring:** Always show the `ZLayer` a reader needs to provide, either as a snippet or an ASCII diagram:

```text
NatsConfig.live ──► Nats.live ──► JetStream.live
```

**Code blocks:** Follow `docs-mdoc-conventions` for modifier choices. Progressive narrative guides use `mdoc:silent` → `mdoc` → `mdoc:silent:nest` → `mdoc:silent:reset` as the guide builds.

**Admonitions:** Use `:::tip` to call out the "I got this" moments. Use `:::warning` for genuine gotchas (e.g. "JetStream must be enabled on the server").

---

## Step 4: Create a Standalone Example (Optional)

If the guide covers a substantial workflow, add a standalone runnable example to `examples/src/main/scala/`:

```scala
import zio.*
import zio.nats.*

object MyFeatureApp extends ZIOAppDefault {
  val program: ZIO[Nats, NatsError, Unit] = {
    // ...
  }

  def run = program.provide(
    NatsConfig.live,
    Nats.live
  )
}
```

Reference it in a "Running the Example" section at the end of the guide:

```text
## Running the Example

Start a NATS server, then:

```text
sbt "zioNatsExamples/runMain MyFeatureApp"
```
```

---

## Step 5: Verify Compilation

Run mdoc on the new file before integrating:

```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "docs/mdoc --in docs/guides/NN-guide-name.md --out website/docs/guides/NN-guide-name.md"
```

**Zero `[error]` lines required.** Fix all errors before proceeding.

---

## Step 6: Integrate

Run `/docs-integrate` to add the guide to the navigation and cross-reference it from related pages.

---

## Step 7: Review Checklist

Before marking the guide as done:

- [ ] The opening paragraph names the concrete task and why it matters
- [ ] Prerequisites list exactly what is needed (no more, no less)
- [ ] `ZLayer` wiring is shown explicitly
- [ ] Every code block has a preceding prose sentence ending in `:`
- [ ] Every code block includes imports
- [ ] No hardcoded result comments (`// Some(...)`, `// Right(...)`)
- [ ] mdoc compiles with zero errors
- [ ] A `:::tip` or `:::warning` is used where genuinely helpful
- [ ] "Next Steps" links to at least one related guide or reference page
- [ ] Tone is warm and confident — the reader should finish feeling capable
