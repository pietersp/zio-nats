# Enrich a Documentation Section with Motivation and Use-Cases

## Overview

A thin section answers *what* but not *why*. Readers landing on such a section cannot judge when to use the API or how it fits into a larger application. This skill turns a thin section into one that answers: what does this return, why does it exist, when is it the right choice, and what does a realistic use look like.

## Signals That a Section Needs Enriching

- Shows only a signature + a trivial example (toy subject, no realistic scenario)
- No mention of alternatives or when *not* to use this operation
- A reader could not decide between this and the nearest related operation from the section alone
- Opening sentence restates the method name without adding context

## Source Research (Do This First)

Before writing a word of prose, read the implementation:

1. **Read the source** — understand what the method actually does, not just its signature
2. **Find the contrast** — locate the nearest alternative (e.g. `Nats#subscribe` vs `Nats#request`, or `Consumer#fetch` vs `Consumer#consume`) and understand the exact difference in semantics, guarantees, and error types
3. **Find real usage** — search examples in `examples/src/main/scala/` and tests in `zio-nats-test/src/test/scala/` to anchor your realistic example
4. **Identify the gap** — ask: "In what situation would a reader need this but *not* the alternative?" That gap is the motivation.

## The Five-Part Expansion Pattern

Replace the thin section with these five parts, in order:

### 1. Opening sentence

State what the method returns and the one-line rule for when to use it. Lead with the return type and the key constraint that distinguishes it from alternatives.

> `Consumer#fetch` returns a bounded `ZStream` of at most `N` messages and then completes. Use it when you need to process a fixed batch and continue — for example, polling a JetStream consumer in a scheduled job.

### 2. Motivation paragraph

Explain the gap the method fills. Name the scenario where the alternative fails or is impractical. Name the concrete contexts (event processors, batch jobs, replay pipelines, audit exporters) where this method is the right tool.

### 3. Contrast sentence or table

State explicitly: "Use X when … Use Y instead when …". One sentence is enough if the distinction is clear; a two-row table if the dimensions are multiple.

| Situation | Right choice |
|-----------|-------------|
| Need exactly N messages, then stop | `Consumer#fetch` |
| Need a continuous stream until cancelled | `Consumer#consume` |

### 4. Signature block

Keep the existing signature block unchanged. Precede it with a bridging sentence ending in `:`.

### 5. Realistic example

Replace any toy example (dummy subject, no meaningful context) with a scenario that could exist in a real application. The scenario should exercise the method's distinguishing behaviour.

Checklist for the example:
- [ ] Models a plausible real scenario (order processor, audit exporter, notification poller)
- [ ] Uses the correct mdoc modifier (see `docs-mdoc-conventions`)
- [ ] Imports everything it needs (`import zio.nats.*`)
- [ ] No hardcoded output comments (`// Some(...)`, `// Right(...)`, etc.)
- [ ] Preceded by a prose sentence ending in `:`
- [ ] Uses Scala 3 syntax with braces (never braceless style)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Motivation paragraph is abstract ("useful in many cases") | Name one concrete scenario. Abstract motivation helps nobody. |
| Contrast buried at the end | Put it before the signature block, after the motivation paragraph |
| Example uses a toy subject ("foo", "test") | Create a realistic subject name that reflects a real use-case ("orders.placed", "sensors.readings") |
| Prose sentence before code does not end with `:` | Every sentence immediately before a code fence must end with `:` |
| Added output comments to show what expressions return | Delete them — use an `mdoc` block to show real evaluated output instead |

## Verification

After enriching the section, run mdoc compilation:

```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "docs/mdoc --in docs/guides/<filename>.md --out website/docs/guides/<filename>.md"
```

**Success criterion:** Zero `[error]` lines. Warnings are acceptable.

Fix all errors before marking the work as complete.
