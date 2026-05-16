# Contributing to DocTruth

Thanks for considering a contribution. The bar for merged code is high, and the bar for new dependencies and new public types is higher. Please read this document end-to-end before opening a non-trivial PR.

## Engineering principles (load-bearing)

These five rules govern every change. They take precedence over local convenience. If a rule feels in the way, the design is usually wrong — push back on the design, not the rule.

1. **Decoupled by default** — one reason to change per public type. Layers communicate only through typed records and sealed interfaces in `ai.doctruth.*` (root) and `ai.doctruth.spi.*`. If a class imports more than three other concrete `ai.doctruth.*` types, split it.
2. **Auditable + debuggable + loggable everywhere** — every external boundary (LLM call, file I/O, network) emits SLF4J events at the documented levels. Every public exception carries a stable string error code plus structured context. **No silent failures, anywhere.**
3. **No god files / classes / functions** — hard limits, refactor rather than lift them:
   - Source file ≤ 300 LOC (excluding Javadoc + imports)
   - Test file ≤ 500 LOC
   - Class / record ≤ 8 public methods OR ≤ 5 record components
   - Method body ≤ 30 LOC
4. **Build, don't synthesize** — check the JDK 25+ standard library and existing direct deps before hand-rolling. Adding a new direct dependency requires an ADR (`docs/adr/000N-why-<libname>.md`) in the same PR. The bar is "JDK + existing deps genuinely cannot do this clearly", not "this lib is convenient".
5. **Elegance over cleverness** — records over classes, sealed interfaces over inheritance, pattern-matching `switch` with no `default`, `Optional<T>` over null. From any public-API call site to the implementation in ≤ 3 hops.

Preferred primitives:

| Need | Use | Avoid |
| --- | --- | --- |
| String similarity / fuzzy match | Apache Commons Text (`JaroWinklerSimilarity`, `LevenshteinDistance`) | Hand-rolled edit distance |
| JSON serialization / schema-facing payloads | Jackson | Ad hoc string concatenation |
| HTTP client | JDK `java.net.http.HttpClient` | Vendor SDKs as public dependencies |
| Retry / backoff | Failsafe (`dev.failsafe:failsafe`) | Manual retry loops |
| Defensive copy | `List.copyOf`, `Map.copyOf`, `Set.copyOf` | Mutable collections escaping records |
| Time / dates | `java.time` | `Date` / `Calendar` |

Public API compatibility:

- Root-package types in `ai.doctruth.*` and SPI types in `ai.doctruth.spi.*` are public API.
- `ai.doctruth.internal.*` types are not public API and may change in any release.
- Removing a public type, changing a public method signature, or changing record components requires a major version bump.
- Internal dependency types must not leak into public method signatures.
- `PublicApiSnapshotTest` snapshots the public SDK surface. If you intentionally change
  public API, regenerate it with:

```bash
mvn -Dtest=ai.doctruth.PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test
```

Review `src/test/resources/ai/doctruth/public-api-snapshot.txt` before committing.

Scope boundaries:

- DocTruth is a document-grounded structured extraction library.
- It is not a chain framework, agent framework, vector-store wrapper, Spring extension, UI viewer, Android library, or document Q&A application.
- New capabilities should be accepted only when they need source evidence, provenance, confidence, schema validation, or audit export semantics.
- Keep the jar single-module while the published artifact stays small and the build remains easy to audit.

Concurrency:

- Public API methods must be thread-safe unless explicitly documented otherwise.
- Prefer immutable records and virtual threads for I/O-bound provider calls.
- Avoid `synchronized` blocks in public-facing code; prefer immutable state or `java.util.concurrent` primitives.

## TDD discipline

Tests stay one small step ahead, not a big leap. After GREEN, **STOP**. New feature → red test → impl → green → commit. One concept per commit. Refactor only on green.

If a PR contains both new tests and a public-API change, split it: one PR for the test (with `@Disabled` or skip), one PR for the implementation that turns it green.

## Setup

DocTruth targets Java 25.

One-time:

```bash
brew install openjdk maven
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
export PATH=$JAVA_HOME/bin:$PATH
```

Build + test:

```bash
mvn test                 # unit tests only (~3s)
mvn verify               # unit + integration + JaCoCo coverage gate (~10s)
mvn spotless:apply       # auto-format
```

`mvn verify` runs the JaCoCo coverage check (line ≥ 90% and branch ≥ 79%
bundle-wide, excluding `ai.doctruth.internal.providers.*` wire records). Lower
the gate only by ADR.

## How to add a new LLM provider

See [ADR 0003](docs/adr/0003-llm-provider-dependency-strategy.md) for the architecture. Steps:

Prefer OpenAI-compatible integration when the target model already exposes the
OpenAI chat-completions shape. Add a new first-class provider only when the
vendor has materially different structured-output semantics, authentication,
retry behavior, or audit metadata.

1. **Wire records** — `src/main/java/ai/doctruth/internal/providers/<vendor>/wire/` with one record per request/response shape (immutable, Jackson-annotated).
2. **HTTP client** — `<Vendor>HttpClient` in `src/main/java/ai/doctruth/internal/providers/<vendor>/`, hand-rolled on `java.net.http.HttpClient`. No vendor SDK on the classpath.
3. **Sealed `LlmProvider`** — add the new permits clause. This is a public-API change → MAJOR version bump (or ship as a separate artifact post-1.0).
4. **Public `<Vendor>Provider` class** — in `ai.doctruth` root package, delegating to the internal `<Vendor>HttpClient`. Mirror the OpenAI-compatible / Anthropic / Gemini shape where possible.
5. **WireMock-backed test class** — `<Vendor>ProviderHttpTest` exercising happy path, retry, HTTP errors, response validation. Recorded responses go in `src/test/resources/wiremock/<vendor>/`.
6. **ADR update** — if the provider introduces an architecturally novel concern (e.g. multimodal request shape, server-sent events), update or add an ADR.

## How to add an SPI implementation

SPIs live in `ai.doctruth.spi.*`. To plug in a custom implementation:

1. Implement the SPI interface (e.g. `SignatureProvider`, `RegionResolver`, `AuditEventListener`).
2. Register it via Java `ServiceLoader` — drop a file under `META-INF/services/ai.doctruth.spi.<InterfaceName>` listing the fully-qualified impl class.
3. Add it to the classpath alongside `doctruth-java`. The DocTruth jar will discover and use it without any API change.

Default implementations stay conservative and no-op where appropriate. Custom implementations can add signing, region enforcement, SIEM listeners, or other organization-specific policy without changing the public API. Community contributions are welcome — open an issue first to discuss scope.

## Pull request checklist

Before opening a PR, confirm:

- [ ] `mvn test` is green
- [ ] `mvn verify` is green (includes integration tests + JaCoCo gate)
- [ ] No file exceeds 300 LOC; no method body exceeds 30 LOC
- [ ] No new entries in `<dependencies>` without an ADR in the same PR
- [ ] Public-API changes flagged in the PR title (e.g. `feat!:` or `BREAKING CHANGE:` footer)
- [ ] Public-API snapshot updated for intentional `ai.doctruth.*` / `ai.doctruth.spi.*` changes
- [ ] Commit message follows Conventional Commits — `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` (one concept per commit, one concept per PR)
- [ ] If your change touches `ai.doctruth.*` or `ai.doctruth.spi.*`, the corresponding `*Test` class is updated
- [ ] If your change touches evidence/audit semantics, update `docs/evidence-schema.md`, `docs/error-handling.md`, and the nearest contract test together

## Code review guarantee

Major architectural decisions are documented as ADRs in [`docs/adr/`](docs/adr) — propose an ADR before writing code if your change touches the public API or introduces a new dependency.

Thanks for keeping the bar high.
