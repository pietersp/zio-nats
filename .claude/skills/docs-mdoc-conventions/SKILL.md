# zio-nats Documentation Conventions

## Source vs Output

**Always edit source files in `docs/`.** The `website/docs/` directory contains mdoc-processed output — never edit it directly. mdoc strips directives and injects evaluated results during compilation.

---

## Compile-Checked Code Blocks with mdoc

This project uses mdoc to compile-check and evaluate Scala code blocks. mdoc runs in **Scala 3** mode — all code in `mdoc:*` blocks must use Scala 3 syntax with **brace style** (never braceless/indentation-based syntax).

**Exception:** Data flow diagrams, structural illustrations, and sbt/shell commands use plain ` ```scala ` or ` ```text ` without mdoc modifiers — they are not compiled.

### Modifier Summary

| Modifier                   | Rendered Output           | Scope                          | Use When                                                                  |
|----------------------------|---------------------------|--------------------------------|---------------------------------------------------------------------------|
| `scala mdoc:compile-only`  | Source code only          | Isolated (no shared state)     | Self-contained examples where evaluated output is NOT needed              |
| `scala mdoc:silent`        | Nothing (hidden)          | Shared with subsequent blocks  | Setting up definitions needed by later blocks                             |
| `scala mdoc:silent:nest`   | Rendered code only        | Shared, wrapped in `object`    | Re-defining names already in scope                                        |
| `scala mdoc`               | Source + evaluated output | Shared with subsequent blocks  | When the evaluated result of expressions should be shown to the reader    |
| `scala mdoc:invisible`     | Nothing (hidden)          | Shared with subsequent blocks  | Importing hidden prerequisites                                            |
| `scala mdoc:silent:reset`  | Nothing (hidden)          | Resets all prior scope         | Starting a clean scope mid-document                                       |
| `scala` (no mdoc)          | Source code only          | Not compiled                   | Pseudocode, ASCII diagrams, shell commands, conceptual snippets           |

### Key Rules

- **`mdoc:compile-only`** is the **default** for self-contained examples where no output needs to be shown. Each block is compiled in isolation — definitions do NOT carry over between `compile-only` blocks.
- **`mdoc:silent`** defines types/values that **subsequent blocks** can reference (scope persists until reset). Nothing is rendered. You cannot redefine the same name — use `silent:nest` for that.
- **`mdoc:silent:nest`** wraps code in an anonymous `object`, allowing you to shadow names from earlier blocks (e.g., redefining a case class with different fields in a later section).
- **`mdoc:silent:reset`** wipes all accumulated scope and starts fresh. Use when switching to a completely different topic mid-document.
- **`mdoc`** (no qualifier) shows **source + evaluated output** (REPL-style). Use this whenever you would otherwise write `// Right(42L)` or `// Some("hello")` — let mdoc render the actual evaluated output instead. Requires definitions to be in scope from a prior `silent` block.
- **`mdoc:invisible`** is like `silent` but signals "hidden imports only." Rare — prefer including imports directly in a `mdoc:silent` setup block.
- **No mdoc** (plain ` ```scala `) — not compiled. Use for pseudocode, ASCII diagrams, type signatures for illustration, or sbt/shell syntax.

### Choosing the Right Modifier

1. Self-contained example where output doesn't need to be shown? → `mdoc:compile-only`
2. Later blocks need these definitions? → `mdoc:silent` (first time) or `mdoc:silent:nest` (redefining)
3. Need a completely clean scope? → `mdoc:silent:reset`
4. **Showing the result of expressions**? → `mdoc:silent` for setup + `mdoc` to render evaluated output. **Never manually write `// result` comments when mdoc can show the real output.**
5. Not real Scala? → plain ` ```scala ` or ` ```text `

---

## Pattern: Setup + Evaluated Output

When a code snippet evaluates expressions whose results are meaningful to the reader, split it into two blocks:

````markdown
```scala mdoc:silent
import zio.nats.*

case class Payload(id: Int, message: String)

val subject = Subject("orders")
```

With `subject` in scope, we can see its underlying value:

```scala mdoc
subject.value
```
````

The `mdoc` block renders as:
```
subject.value
// val res0: String = "orders"
```

Do **not** use `mdoc:compile-only` and manually write `// "orders"` — always prefer the live evaluated output from `mdoc`.

---

## Pattern: Progressive Narrative (Guides)

How-to guides build code progressively — use shared-scope modifiers more than in reference pages:

1. **Domain setup** (case classes, imports): `mdoc:silent` — hidden, but in scope for all subsequent blocks.
2. **First example** (showing how something works): `mdoc` — show source + output so the reader sees the result.
3. **Building on the example** (adding a feature): `mdoc:silent:nest` if redefining a name, `mdoc` if showing output.
4. **New topic within the guide**: `mdoc:silent:reset` to start clean, then `mdoc:silent` for new setup.
5. **Final "putting it together"**: `mdoc:compile-only` — fully self-contained, copy-paste ready.

---

## Running mdoc

**Single file (preferred — fast):**
```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat "docs/mdoc --in docs/guides/01-pubsub.md --out website/docs/guides/01-pubsub.md"
```

**All files (slow — ~90 seconds, avoid unless needed):**
```text
/c/Users/pieter/AppData/Local/Coursier/data/bin/sbt.bat docs/mdoc
```

**Success criterion:** The output contains **zero `[error]` lines**. Warnings are acceptable.

If mdoc reports errors, fix them immediately before marking any work as complete.

---

## Docusaurus Admonitions

Use Docusaurus admonition syntax for callouts. Use admonitions **sparingly** — at most 3–4 per document. They should highlight genuinely important information, not decorate every section.

```
:::note
Additional context or clarification.
:::

:::tip
Helpful shortcut or best practice — great for the "I got this" moments.
:::

:::warning
Common mistake or gotcha to avoid.
:::

:::info
Background information that is useful but not essential.
:::

:::danger
Serious risk: data loss, incorrect behaviour, or security issue.
:::
```
