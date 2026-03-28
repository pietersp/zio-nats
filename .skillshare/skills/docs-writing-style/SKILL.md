# zio-nats Documentation Writing Style

## Tone

The documentation should make readers feel: **"I got this — this is great."** Every page is a conversation with someone who wants to build something real with NATS and ZIO. Be warm, be direct, celebrate small wins. A reader who just wired up their first subscription should feel genuinely good about it.

Concrete tone rules:
- After a meaningful step, briefly name what was accomplished: "We now have a live subscription backed by a `ZStream`."
- Use `:::tip` admonitions to highlight genuinely helpful shortcuts or "aha" moments — not to pad content.
- Never be condescending. Never be cold. Trust the reader.

---

## Prose Style Rules

1. **Person pronouns**: Use "we" when guiding the reader or walking through examples ("we can create...", "we need to..."). Use "you" when addressing the reader's choices ("if you need...", "you might want to...").
2. **Tense**: Present tense only ("returns", "creates", "modifies").
3. **No padding/filler**: No filler phrases like "as we can see" or "it's worth noting that". Just state the fact.
4. **Bullet capitalization**: When a bullet point is a full sentence, start it with a capital letter.
5. **No manual line breaks in prose**: Do not hard-wrap paragraph text at a fixed column. Write each paragraph as one continuous line.
6. **ASCII art usage**: Use it for diagrams showing data flow, layer wiring, or architecture. Readers find these very helpful for understanding how pieces fit together.
7. **Link to related docs**: Use relative paths with `.md` extensions, e.g., `[JetStream guide](./03-jetstream.md)`.

---

## Referencing Types, Operations, and Constructors

8. **Always qualify method/constructor names**: Never refer to a method by its bare name. Write `Nats#publish`, not `publish`; write `KeyValue#get`, not `get`. The type name is essential context. This rule applies everywhere: in prose, headings, and bullet points. Method/operation references must be wrapped in backticks.

   **Examples:**
   - **Instance methods** — use `TypeName#methodName`: `Nats#publish`, `Nats#subscribe`, `KeyValue#get`, `JetStream#publish`
   - **Companion object/static members** — use `TypeName.methodName`: `Nats.live`, `KeyValue.bucket`, `NatsConfig.live`
   - **In headings** — same rules apply: `### Nats#subscribe`, `#### KeyValue#put` (always in backticks)

9. **Type name alone rule**: When talking about the type itself (not a method), use only its name: "wire up `JetStream`", "`NatsError` is a sealed ADT".

---

## Frontmatter Titles

10. **No duplicate markdown heading**: Do not create a markdown `#` heading that duplicates the frontmatter `title`. The frontmatter title is sufficient. Start document content with a `##` section heading.

---

## Heading and Code Block Layout Rules

11. **Heading hierarchy**: Use `##` for major sections, `###` for subsections, and `####` for subsubsections. All three levels are fully supported and encouraged.
12. **No bare subheaders**: Never place a `###` or `####` subheader immediately after a `##` header with nothing in between. Always write at least one sentence of explanation before the first subheader.
13. **No lone subheaders**: Never create a subsection with only one child. If a `##` section would have only one `###`, remove the subheader and place the content directly under the parent heading.
14. **When to use `####`**: Group multiple related items (operations, variants, use-cases) under a single `###` heading, using `####` for each item. Example: `### Publishing` → `#### Fire-and-forget` → `#### With headers`.
15. **Every code block must be preceded by an introductory prose sentence**: The content immediately before a code block's opening fence must always be a prose sentence — never a heading alone and never blank space alone.
    - After a heading: write at least one sentence before the first code block.
    - Between two consecutive code blocks: write a short bridging sentence.
    - **The sentence must end with a colon (`:`)**. A colon signals to the reader that code follows.

---

## Code Block Rules

16. **Always include imports**: Every code block must start with the necessary import statements.
17. **One concept per code block**: Each code block demonstrates one cohesive idea.
18. **Prefer `val` over `var`**: Use immutable patterns everywhere.
19. **Never hardcode expression output in comments**: Do not annotate expression results with inline comments such as `// Some("hello")` or `// Right(42)` — these go stale. Restructure the block so mdoc evaluates and renders the output. See **`docs-mdoc-conventions`** for the Setup + Evaluated Output pattern.
20. **Code snippet description**: Explain what each code block does and why it is relevant. Don't show code without context.

---

## Scala Version and Syntax

21. **Scala 3 language features**: All code uses Scala 3: `import x.*`, `given`, `using`, `enum`, extension methods, `opaque type`. Never use Scala 2 syntax for these constructs (`import x._`, `implicit`, etc.).
22. **Brace syntax required**: Always use `{ }` braces. Never use braceless/indentation-based Scala 3 syntax.

   **Correct:**
   ```scala
   for {
     nats <- ZIO.service[Nats]
     _    <- nats.publish(Subject("greetings"), "Hello")
   } yield ()
   ```

   **Wrong (braceless — do not use):**
   ```scala
   for
     nats <- ZIO.service[Nats]
     _    <- nats.publish(Subject("greetings"), "Hello")
   yield ()
   ```

23. **ZIO environment pattern**: Show `ZIO[Nats, NatsError, A]` types and `ZLayer` composition explicitly. Readers need to understand how services wire together.

---

## Table Formatting

24. **Pad column alignment**: Pad column headers and separators with spaces to align content vertically. This makes tables more scannable.

---

## ZIO-Specific Conventions

25. **Layer wiring**: When showing layer dependencies, use an ASCII diagram or a `ZLayer` composition snippet. Never leave the reader guessing which `ZLayer` to provide.
26. **Error types**: Always name the specific `NatsError` subtype(s) a method can return when documenting failure cases.
27. **`import zio.nats.*`**: Remind readers in prerequisite sections that `import zio.nats.*` is the only import they need — all types and re-exports are available from it.
