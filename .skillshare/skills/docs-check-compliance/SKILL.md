---
name: docs-check-compliance
description: Check a zio-nats documentation page against the project documentation standards.
---

# Documentation Compliance Audit Workflow

This workflow systematically audits documentation files against a rule skill and fixes every violation found.

## Invocation

```
/docs-check-compliance <path-to-doc> <rule-skill-name>
```

Example:
```
/docs-check-compliance docs/guides/01-pubsub.md docs-writing-style
```

---

## Step 1: Load the Rules

Invoke the specified rule skill using the Skill tool to load all enforced rules into context.

## Step 2: Read the Documentation File

Read the target file using an absolute path:

```text
C:\Users\pieter\Code\zio-nats\docs\<path-to-file>
```

## Step 3: Enforce Rules Sequentially

For **each rule** in the loaded skill:

1. **Adversarial verification** — assume full compliance, then actively search for violations. Quote the offending line(s) with line numbers.
2. If a violation is found: make the minimal fix that resolves the violation without altering intent.
3. **Commit immediately** with a message in this format:
   ```text
   docs(<filename-stem>): fix <rule-name-or-short-description>
   ```
4. Move to the next rule. Do not batch fixes across rules.

## Step 4: Compile and Verify

After all rule violations are fixed, run mdoc to confirm the document still compiles:

```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "docs/mdoc --in docs/<path> --out website/docs/<path>"
```

If mdoc reports errors, fix them with a separate commit:
```text
docs(<filename-stem>): fix mdoc compilation error
```

## Step 5: Report Results

Summarize:
- Which rules had violations, and what was fixed
- Which rules had zero violations
- Final mdoc compilation status (pass / errors fixed)

---

## Key Constraints

- **One commit per violation** — never batch multiple rule fixes into one commit
- **Minimal edits** — preserve document intent; only change what the rule requires
- **Absolute paths** — always use the full Windows path when reading files
- **Fix before claiming done** — do not report success until mdoc compiles clean
