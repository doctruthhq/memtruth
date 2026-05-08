# doctruth-java — Canonical Agent Instructions

A Java library for **document-truth-based AI extraction** — every LLM-extracted field carries a verifiable evidence chain (source page + line + confidence + timestamps).

> **One-line purpose**: Make every LLM extraction in Java enterprise stacks **auditable, reproducible, and citation-grounded**.

---

## Why this project exists

Enterprise AI demos look impressive until someone asks "where did this number come from?" In Python, libraries like `instructor` + `Docling` + custom evidence pipelines have made schema-bound extraction practical. **In Java, no equivalent small, evidence-first primitive exists.**

Existing Java AI tooling — LangChain4j, Spring AI, Watsonx Java SDK — covers the basic loop (LLM call → parsed object). They do **not** cover:
- Per-field source attribution (which page + which line of the source PDF justifies this value)
- Per-section confidence scoring (calibrated against domain rubrics)
- Bi-temporal provenance (when the source was published vs when the field was extracted)
- Smart context assembly when documents exceed model context windows
- Audit-trail JSON suitable for regulated-industry compliance (financial services, construction, healthcare)

**doctruth-java fills this gap.** It is **not** a wrapper around LangChain4j or Spring AI. It is a small, focused, framework-agnostic library that drops into any Java project that already speaks Anthropic / OpenAI / Gemini / DeepSeek.

---

## Origin: Lessons from production extraction systems

The patterns this library codifies came from production document-extraction systems where users needed every extracted claim to point back to source text. The useful, generalisable lessons were:

1. **3-stage ingest pipeline** for complex documents:
   - structured PDF/DOCX parsing that preserves layout
   - entity enrichment over the parsed text
   - schema-bound LLM extraction with per-section confidence scoring
2. **Smart context assembly**: bounded LLM context should prioritise critical sections instead of blind truncation.
3. **Page-level evidence attribution**: every finding should trace back to its source page, line, and quote.
4. **13-dimension contract risk audit**: payment terms · duration · breach penalties · change orders · material price adjustment · insurance · force majeure · dispute resolution · QA/QC · safety · project management · handover docs · subcontracting.
5. **Capability library RAG** (the moat): structured DB of 12 qualification categories · 17 personnel certificate types · 18 project performance types · all with expiry tracking → fed into the chapter generator with verifiable citations.
6. **4-stage downstream generation**:
   - outline extraction
   - evidence mapping
   - structured generation
   - consistency checking

**These are domain-specific implementations.** This library extracts the **generalisable patterns** behind them and ships them as Java primitives.

---

## Architecture

### Layers (from low-level to high-level)

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Audit + Export (JSON-LD provenance, FHIR-compatible       │
│                    citation format, downstream ingestion)    │
├──────────────────────────────────────────────────────────────┤
│ 4. High-level fluent API (DocTruth.from(provider).parse(...) │
│                          .extract(...).withProvenance())     │
├──────────────────────────────────────────────────────────────┤
│ 3. Smart context assembly (priority-section truncation,      │
│    sliding-window for large docs, deduplication)             │
├──────────────────────────────────────────────────────────────┤
│ 2. Evidence-attributed extraction (LLM call wrapper that     │
│    enforces source-citation contract on every extracted     │
│    field; retry on missing evidence)                         │
├──────────────────────────────────────────────────────────────┤
│ 1. Document parsing (PDF/DOCX → structured sections with     │
│    page+line+offset preserved; Apache PDFBox + Apache POI)  │
└──────────────────────────────────────────────────────────────┘
```

### Public API contracts (DO NOT BREAK without a major version bump)

```java
package ai.doctruth;

// Layer 1: parsing
public sealed interface ParsedSection permits TextSection, TableSection, FigureSection { ... }
public record SourceLocation(int pageStart, int pageEnd, int lineStart, int lineEnd, int charOffset) {}
public record ParsedDocument(String docId, List<ParsedSection> sections, DocumentMetadata metadata) {}
public enum BlockKind { HEADING, BODY, LIST, OTHER }
public record TextSection(String text, SourceLocation location, BlockKind kind) implements ParsedSection {
    // 2-arg convenience ctor defaults kind to BlockKind.OTHER (backward-compat with pre-BlockKind callers)
}
public final class PdfDocumentParser  { public static ParsedDocument parse(Path pdfPath)  throws ParseException; }
public final class DocxDocumentParser { public static ParsedDocument parse(Path docxPath) throws ParseException; }
public final class XlsxDocumentParser { public static ParsedDocument parse(Path xlsxPath) throws ParseException; }
public final class CsvDocumentParser  { public static ParsedDocument parse(Path csvPath)  throws ParseException; } // ADR 0007

// Layer 2 + 4: extraction with evidence
public final class DocTruth {
    public static DocTruth from(LlmProvider provider) { ... }
    public ExtractionBuilder<T> extract(String prompt, Class<T> type) { ... }
    public JsonExtractionBuilder extractJson(String prompt, JsonSchema schema) { ... }
}

public final class ExtractionBuilder<T> {
    public ExtractionBuilder<T> withProvenance() { ... }      // require citation per field
    public ExtractionBuilder<T> withBitemporal() { ... }      // record extracted_at + source_published_at
    public ExtractionBuilder<T> withConfidence() { ... }      // require per-section confidence
    public ExtractionBuilder<T> withMaxRetries(int n) { ... } // retry on validation failure
    public ExtractionBuilder<T> withContextStrategy(ContextStrategy s) { ... }
    public ExtractionBuilder<T> withSourcePublishedAt(Instant t) { ... }
    public <V> ExtractionBuilder<T> withFieldConstraint(String fieldPath, Class<V> valueType, Predicate<V> predicate, String message) { ... }
    public ExtractionBuilder<T> withObjectConstraint(Predicate<T> predicate, String message) { ... }
    public ExtractionBuilder<T> withAuditListener(AuditEventListener listener) { ... }
    public ExtractionResult<T> run(ParsedDocument doc) throws ExtractionException { ... }
}

// External schema / Pydantic JSON Schema interop
public final class JsonSchema {
    public static JsonSchema from(String json) { ... }
    public static JsonSchema from(Path path) { ... }
    public JsonNode node() { ... } // defensive copy
}

public final class JsonExtractionBuilder {
    public JsonExtractionBuilder withProvenance() { ... }
    public JsonExtractionBuilder withBitemporal() { ... }
    public JsonExtractionBuilder withConfidence() { ... }
    public JsonExtractionBuilder withMaxRetries(int n) { ... }
    public JsonExtractionBuilder withContextStrategy(ContextStrategy s) { ... }
    public JsonExtractionBuilder withSourcePublishedAt(Instant t) { ... }
    public JsonExtractionBuilder requireCitation(String fieldPath) { ... }
    public ExtractionResult<JsonNode> runJson(ParsedDocument doc) throws ExtractionException { ... }
}

public record ExtractionResult<T>(
    T value,
    Map<String, Citation> citations,           // field path → source location
    Map<String, Confidence> confidence,        // field path → confidence score
    Provenance provenance                      // model, version, timestamps, retry count
) {}

public record Citation(SourceLocation location, String exactQuote, double matchScore) {}
public record Confidence(double score, String rationale) {}
public record Provenance(String model, String modelVersion, Instant extractedAt, Instant sourcePublishedAt, int retries) {}

// Layer 3: smart context
public sealed interface ContextStrategy permits PriorityTruncate, SlidingWindow, Hierarchical { ... }
public record PriorityTruncate(List<String> prioritySectionPatterns, int maxTokens) implements ContextStrategy {}

// Provider abstraction (Layer 2 backend)
public sealed interface LlmProvider permits AnthropicProvider, OpenAiProvider, GeminiProvider, DeepSeekProvider { ... }
```

---

## Engineering principles (load-bearing — read before any non-trivial change)

These five rules govern every line of code in `doctruth-java`. They take precedence over
local convenience. If a rule feels in the way, the design is usually wrong — push back on
the design, not the rule.

### 1. Decoupled by default

Each public class / record / interface has ONE reason to change. Layers communicate ONLY
through the typed records and sealed interfaces in the `ai.doctruth.*` root package — never
by leaking a concrete implementation type from one layer into another.

If a class imports more than three other concrete `ai.doctruth.*` types, the design is wrong
— split into smaller collaborators, or move shared shape into a record / sealed interface
in the root package.

### 2. Auditable + debuggable + loggable everywhere

Every external boundary (LLM call, file I/O, network, anything that can fail in production)
emits SLF4J events at the documented levels (see "Logging" below). Every public exception
carries a stable string error code plus structured context (model name, retry count, source
filename, page number — whatever applies).

**No silent failures.** Substring page-attribution that quietly returns no page is the
canonical anti-pattern this library exists to replace. Match failures emit a warning AND surface a low `matchScore` on the
`Citation` so the caller can decide; they do not vanish.

### 3. No god files / classes / functions

Hard limits — if approaching, the design is wrong. Split rather than lift the limit.

| Unit | Limit |
| --- | --- |
| Source file | ≤ 300 LOC (excluding Javadoc + imports) |
| Test file | ≤ 500 LOC |
| Class / record | ≤ 8 public methods, OR ≤ 5 record components |
| Function body | ≤ 30 LOC |

A "god class" is any class that ends up coordinating four or more responsibilities (parsing
+ HTTP + retry + caching). Refactor into single-responsibility collaborators.

### 4. Build, don't synthesize

Before hand-rolling any utility, check (a) the JDK 25+ standard library and (b) already-
declared direct dependencies. Reach for community-standard libraries over custom code:

| Need | Use | NOT |
| --- | --- | --- |
| String similarity / fuzzy match | Apache Commons Text (`LevenshteinDistance`, `JaroWinklerSimilarity`) | hand-rolled DP or naive `.contains()` |
| JSON Schema from records | Jackson (`tools.jackson.jsonSchema` / `jackson-module-jsonSchema`) | reflection by hand |
| HTTP client | JDK `java.net.http.HttpClient` | OkHttp, Apache HttpClient |
| Retry / circuit breaker / rate limit | Failsafe (`dev.failsafe:failsafe`) or Resilience4j | manual `for (int i = 0; i < n; i++)` retry loops |
| Defensive copy | `List.copyOf`, `Map.copyOf`, `Set.copyOf` | `new ArrayList<>(list)` + `Collections.unmodifiableList` |
| Time / dates | `java.time.Instant`, `Duration`, `OffsetDateTime` | `java.util.Date`, `Calendar` |
| String constants of fixed values | `enum` | `static final String` collections |
| Concurrency / thread pools | `Thread.ofVirtual()`, `StructuredTaskScope` | hand-rolled `synchronized` |

**Adding a new direct dependency requires an ADR** (`docs/adr/000N-why-<libname>.md`)
justifying the choice in the same PR. The bar to add a dep is "the JDK + existing deps
genuinely cannot do this clearly", not "this lib is convenient".

### 5. Elegance over cleverness

A reader new to the codebase should reach the implementation from any public-API call site
in ≤ 3 hops. If a change requires reading 4+ files to understand, the design is wrong.

Idioms to prefer:

- **Records over classes** for immutable data (already in use).
- **Sealed interfaces over inheritance** for closed type families (already in use).
- **Pattern-matching `switch` with no default** over instanceof chains.
- **`Optional<T>`** for "may be absent" return types; never `null`.
- **Compact constructors** for record invariants (already in use).
- **Text blocks** for any literal > 1 line.
- **`var`** when the right-hand side names the type clearly; explicit type otherwise.

Idioms to avoid:

- Static utility classes named `*Utils` / `*Helpers` — usually a sign that behavior belongs
  on a record or interface instead.
- Returning `null` to signal "absent" — return `Optional.empty()` or throw.
- Catch-and-rethrow without adding context — either let it propagate or wrap with cause.
- Comments that restate the code — comment WHY, not WHAT.

---

## Code style + conventions

### Java version
- **Java 25** minimum (records, sealed classes, virtual threads, pattern matching for switch, text blocks)
- Java 26+ features only behind preview flags + tests gated on `java.version`

### Module structure
- Single Maven module to start. Split into `doctruth-core`, `doctruth-anthropic`, `doctruth-openai` etc. only if the JAR exceeds 5MB
- Package root: `ai.doctruth`
- Internal-only packages live under `ai.doctruth.internal.*` and are explicitly NOT public API (Javadoc `@hidden`)

### Naming
- Use `Truth`-prefix terminology for the audit primitives (e.g. `TruthCitation`, `TruthProvenance`) but only as record names; package paths drop the prefix
- Avoid `*Service`, `*Manager`, `*Handler` suffixes — they're hollow
- Abbreviations: `Llm` not `LLM`, `Ai` not `AI`, `Api` not `API` (matches Java standard library conventions)

### Imports
- No wildcard imports
- Static imports only for `Assertions.*` in tests and `List.of` / `Map.of` style factories

### Tests
- JUnit 5 + AssertJ
- Test class per public class: `DocTruthTest` for `DocTruth`
- Integration tests under `src/integrationTest/java`, gated by `IT` suffix on class name
- Hit a real Anthropic API in CI **only** for `nightly` profile; main CI uses recorded responses (WireMock)

### Logging
- SLF4J facade only; do not import logback or log4j directly
- Levels: `error` for invariant violations, `warn` for retry-eligible failures, `info` for major lifecycle events, `debug` for per-LLM-call detail, `trace` for per-token detail (off by default)

### Error handling
- Public API throws checked `ExtractionException`, `ParseException`, `ProviderException`
- Internal failures use unchecked exceptions in `ai.doctruth.internal.*`
- Never swallow exceptions; if you mean "ignore this", log at `debug` and add a comment explaining why

### Concurrency
- Default to virtual threads (`Thread.ofVirtual()`) for I/O-bound LLM calls
- No `synchronized` blocks — use `java.util.concurrent.locks` or immutable data
- Public API methods must be thread-safe unless explicitly documented otherwise

---

## Build + dev workflow

```bash
# Build
mvn clean install -DskipTests

# Test (unit only)
mvn test

# Test (with recorded LLM responses)
mvn verify -P recorded

# Test (hit real Anthropic API — requires ANTHROPIC_API_KEY)
mvn verify -P live

# Format + lint
mvn spotless:apply
mvn checkstyle:check
```

---

## What this project IS

- A **lightweight, single-jar** library (target <500KB) that drops into any Java 25+ project
- Framework-agnostic — does NOT depend on Spring, LangChain4j, Quarkus, or any framework
- Provider-agnostic — same API works against Anthropic, OpenAI, Gemini, DeepSeek
- Document-format-aware — first-class support for PDF (Apache PDFBox) and DOCX (Apache POI)
- Schema-contract-aware — accepts Java records/classes and caller-supplied JSON Schema, including common Pydantic v2 exports
- **Evidence-first** — every extraction MUST be able to cite source

## What this project is NOT (refuse scope creep)

- ❌ NOT another "Java port of LangChain" — leave general-purpose chains to LangChain4j
- ❌ NOT a vector-store wrapper — use pgvector / Qdrant / Pinecone direct clients
- ❌ NOT an agentic framework — leave agents to dedicated libraries
- ❌ NOT an MCP SDK (MCP Java SDK already exists, official, Spring 2025-02)
- ❌ NOT a UI / viewer — keep it pure backend / CLI
- ❌ NOT an Android library (Java 25 + Apache PDFBox limitations)
- ❌ NOT a "doc translation" or "doc-Q&A chatbot" library — those are downstream applications, not this layer
- ❌ NOT a Python runtime bridge — Pydantic compatibility means build-time/exported JSON Schema interop, not importing Python in Java production

If a contributor proposes scope expansion: ask "Does this primitive need source-evidence + provenance + confidence semantics?" If no, redirect to a sibling library.

---

## Open-source release plan

| Stage | Trigger | Action |
|-------|---------|--------|
| `v0.1.0-alpha` | Core API + Anthropic provider + PDF parser | Tag, push to GitHub, write `Show HN` post |
| `v0.2.0-alpha` | OpenAI + Gemini providers added | Public alpha, gather feedback in GitHub Discussions |
| `v0.3.0-beta` | DOCX parser + sliding-window context strategy | Submit to AsciiDoctor / Java Weekly newsletter |
| `v0.4.0-beta` | Used in 1 production system (a downstream production integration or external) | Maven Central release |
| `v1.0.0` | API stable for 90 days, ≥3 production users | Stable release, blog series, conference talk submission |

---

## Communication / commit conventions

- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`)
- PR titles match commit format
- One concept per commit / PR
- Changelogs auto-generated via `release-please` from Conventional Commits

---

## License & Trademark

Code: **Apache License 2.0** (per [ADR 0008](docs/adr/0008-license-apache-2-0-and-trademark.md)). Picked over MIT for the patent grant; picked over BSL/AGPL/SSPL to keep the door open at regulated-industry procurement teams that auto-ban non-OSI-approved licences.

`doctruth`, `doctruth.ai`, and the logo are trademarks of doctruthhq — see the [NOTICE](NOTICE) file. Forks may use the code; they may NOT use the brand.

---

## Maintainer

**doctruthhq maintainers**
github.com/doctruthhq

This library is intentionally maintainer-driven (not "community-driven from day 0") for the first 12 months. Issues are triaged within 5 business days; pull requests reviewed within 10. Major architectural decisions are documented as ADRs (Architecture Decision Records) in `docs/adr/`.
