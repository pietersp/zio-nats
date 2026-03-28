# Verify Documentation Compliance

Runs a full compliance check on a documentation file: writing style, mdoc conventions, and compilation.

## Invocation

```
/docs-verify-compliance <path-to-doc>
```

Example:
```
/docs-verify-compliance docs/guides/01-pubsub.md
```

---

## Three Sequential Steps

Run these in order. Each step must pass before moving to the next.

### Step 1: Writing Style Check

```
/docs-check-compliance <path-to-doc> docs-writing-style
```

Address all violations found. Each violation gets its own commit.

### Step 2: mdoc Conventions Check

```
/docs-check-compliance <path-to-doc> docs-mdoc-conventions
```

Address all violations found. Each violation gets its own commit.

### Step 3: mdoc Compilation

```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "docs/mdoc --in <path-to-doc> --out website/<path-to-doc>"
```

**Success criterion:** Zero `[error]` lines.

If compilation fails, fix each error with a separate commit, then re-run until clean.

---

## Definition of Done

All three checks pass with zero violations and zero compilation errors.
