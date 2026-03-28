# Documentation Gap Analysis

Scans the zio-nats public API and identifies areas that lack documentation coverage. Produces a structured report for prioritisation.

---

## Step 1: Automated Scan

Identify all public services and types in the core library:

```bash
# Find all public traits (services) and their methods
grep -r "^trait " zio-nats-core/src/main/scala/zio/nats/ --include="*.scala" -l

# Find all public case classes and models
grep -r "^case class\|^sealed trait\|^enum " zio-nats-core/src/main/scala/zio/nats/ --include="*.scala"
```

Collect:
- All public service traits: `Nats`, `JetStream`, `JetStreamManagement`, `KeyValue`, `KeyValueManagement`, `ObjectStore`, `ObjectStoreManagement`, `NatsService`, `ServiceDiscovery`, `Consumer`, `OrderedConsumer`
- All public model types from `NatsCoreTypes.scala`, `NatsModels.scala`, `NatsError.scala`, `JetStreamModels.scala`, `KeyValueModels.scala`, `ObjectStoreModels.scala`, `ServiceModels.scala`
- All config types: `NatsConfig`, `NatsAuth`, `NatsTls`, `StreamConfig`, `ConsumerConfig`, `KeyValueConfig`, `ObjectStoreConfig`, `ServiceConfig`

## Step 2: Map Against Existing Coverage

Read the existing documentation structure:

```text
docs/guides/    — 01-pubsub, 02-serialization, 03-jetstream, 04-key-value,
                  05-object-store, 06-configuration, 07-connection-events, 08-testing
docs/reference/ — 01-nats-config, 02-error-handling, 03-modules
docs/concepts/  — 01-architecture
```

For each public service or type found in Step 1, check whether it is:
- **Fully covered** — dedicated guide or reference section with examples
- **Partially covered** — mentioned but lacking examples or motivation
- **Missing** — not documented at all

## Step 3: Classify by Priority

| Priority | Criteria |
|----------|----------|
| Critical | Public service (`Nats`, `JetStream`, `KeyValue`, etc.) with no guide |
| High     | Public service with a guide but missing key operations |
| Medium   | Public model or config type with no reference documentation |
| Low      | Internal helpers, `private[nats]` types, or types that are self-explanatory from context |

## Step 4: Depth Review of Existing Pages

For each existing guide and reference page, audit:
- Are all public methods of the covered service documented?
- Are there realistic examples (not just toy subjects)?
- Are error cases documented (`NatsError` subtypes)?
- Are `ZLayer` dependencies shown?
- Are there broken or missing cross-references?

## Step 5: Conceptual Gap Audit

Beyond API coverage, check for missing conceptual content:
- Is there a guide for the Service Framework (Micro protocol)?
- Is there a migration or upgrade guide?
- Is there documentation on running in production (connection pooling, reconnect strategies)?
- Is there a guide on combining multiple services in one application?

## Step 6: Produce the Report

Write the report to `docs/undocumented-report.md`:

```markdown
# Documentation Gap Report

Generated: <date>

## Coverage Statistics

- Public services documented: X / Y
- Public model types documented: X / Y
- Existing pages audited: N

## Critical Gaps (no documentation)

- [ ] ServiceDiscovery — no guide or reference section (~M hours)
- ...

## High-Priority Gaps (partial coverage)

- [ ] JetStream — ordered consumers not documented
- [ ] KeyValue — watch options not covered
- ...

## Medium-Priority Gaps (models and config)

- [ ] StreamConfig — fields not fully documented in reference
- ...

## Depth Issues (existing pages)

- [ ] 01-pubsub.md — queue group section lacks realistic example
- ...

## Conceptual Gaps

- [ ] No guide for Service Framework / Micro protocol
- [ ] No production operations guide
- ...
```

The report uses checkboxes for task tracking. It is internal — not part of the published documentation site.
