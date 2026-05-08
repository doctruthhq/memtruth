# Contributing to doctruth-java

Thanks for considering a contribution. This library is intentionally maintainer-driven for its first 12 months — the bar for merged code is high, and the bar for new dependencies and new public types is higher. Please read this document end-to-end before opening a non-trivial PR.

## Engineering principles (load-bearing)

The five rules in [AGENTS.md §"Engineering principles"](AGENTS.md) govern every change. They take precedence over local convenience. If a rule feels in the way, the design is usually wrong — push back on the design, not the rule.

1. **Decoupled by default** — one reason to change per public type. Layers communicate only through typed records and sealed interfaces in `ai.doctruth.*` (root) and `ai.doctruth.spi.*`. If a class imports more than three other concrete `ai.doctruth.*` types, split it.
2. **Auditable + debuggable + loggable everywhere** — every external boundary (LLM call, file I/O, network) emits SLF4J events at the documented levels. Every public exception carries a stable string error code plus structured context. **No silent failures, anywhere.**
3. **No god files / classes / functions** — hard limits, refactor rather than lift them:
   - Source file ≤ 300 LOC (excluding Javadoc + imports)
   - Test file ≤ 500 LOC
   - Class / record ≤ 8 public methods OR ≤ 5 record components
   - Method body ≤ 30 LOC
4. **Build, don't synthesize** — check the JDK 21+ standard library and existing direct deps before hand-rolling. Adding a new direct dependency requires an ADR (`docs/adr/000N-why-<libname>.md`) in the same PR. The bar is "JDK + existing deps genuinely cannot do this clearly", not "this lib is convenient".
5. **Elegance over cleverness** — records over classes, sealed interfaces over inheritance, pattern-matching `switch` with no `default`, `Optional<T>` over null. From any public-API call site to the implementation in ≤ 3 hops.

## TDD discipline

Tests stay one small step ahead, not a big leap. After GREEN, **STOP**. New feature → red test → impl → green → commit. One concept per commit. Refactor only on green.

If a PR contains both new tests and a public-API change, split it: one PR for the test (with `@Disabled` or skip), one PR for the implementation that turns it green.

## Setup

One-time:

```bash
brew install openjdk@21 maven
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
export PATH=$JAVA_HOME/bin:$PATH
```

Build + test:

```bash
mvn test                 # unit tests only (~3s)
mvn verify               # unit + integration + JaCoCo coverage gate (~10s)
mvn spotless:apply       # auto-format
```

`mvn verify` runs the JaCoCo coverage check (line ≥ 85% bundle-wide, excluding `ai.doctruth.internal.providers.*` wire records). Lower the gate only by ADR.

## How to add a new LLM provider

See [ADR 0003](docs/adr/0003-llm-provider-dependency-strategy.md) for the architecture. Steps:

1. **Wire records** — `src/main/java/ai/doctruth/internal/providers/<vendor>/wire/` with one record per request/response shape (immutable, Jackson-annotated).
2. **HTTP client** — `<Vendor>HttpClient` in `src/main/java/ai/doctruth/internal/providers/<vendor>/`, hand-rolled on `java.net.http.HttpClient`. No vendor SDK on the classpath.
3. **Sealed `LlmProvider`** — add the new permits clause. This is a public-API change → MAJOR version bump (or ship as a separate artifact post-1.0).
4. **Public `<Vendor>Provider` class** — in `ai.doctruth` root package, delegating to the internal `<Vendor>HttpClient`. Mirror the Anthropic / OpenAI / Gemini / DeepSeek shape.
5. **WireMock-backed test class** — `<Vendor>ProviderHttpTest` exercising happy path, retry, HTTP errors, response validation. Recorded responses go in `src/test/resources/wiremock/<vendor>/`.
6. **ADR update** — if the provider introduces an architecturally novel concern (e.g. multimodal request shape, server-sent events), update or add an ADR.

## How to add an SPI implementation (commercial / community)

See [ADR 0006](docs/adr/0006-oss-commercial-separation-design.md) for the open-core split and the SPI catalog. SPIs live in `ai.doctruth.spi.*`. To plug in a custom implementation:

1. Implement the SPI interface (e.g. `SignatureProvider`, `RegionResolver`, `AuditEventListener`).
2. Register it via Java `ServiceLoader` — drop a file under `META-INF/services/ai.doctruth.spi.<InterfaceName>` listing the fully-qualified impl class.
3. Add it to the classpath alongside `doctruth-java`. The OSS jar will discover and use it without any API change.

OSS-tier default impls are no-ops; commercial-tier impls (`ai.doctruth:doctruth-enterprise`) ship signing / region enforcement / SIEM listeners. Community contributions are welcome — open an issue first to discuss scope.

## Pull request checklist

Before opening a PR, confirm:

- [ ] `mvn test` is green
- [ ] `mvn verify` is green (includes integration tests + JaCoCo gate)
- [ ] No file exceeds 300 LOC; no method body exceeds 30 LOC
- [ ] No new entries in `<dependencies>` without an ADR in the same PR
- [ ] Public-API changes flagged in the PR title (e.g. `feat!:` or `BREAKING CHANGE:` footer)
- [ ] Commit message follows Conventional Commits — `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` (one concept per commit, one concept per PR)
- [ ] If your change touches `ai.doctruth.*` or `ai.doctruth.spi.*`, the corresponding `*Test` class is updated
- [ ] If your change touches the AU compliance posture, `docs/compliance/au-audit-readiness.md` and `AustralianAuditContractTest` are updated together

## Code review guarantee

Per [AGENTS.md](AGENTS.md) "Maintainer": issues triaged within 5 business days, pull requests reviewed within 10. Major architectural decisions are documented as ADRs in [`docs/adr/`](docs/adr) — propose an ADR before writing code if your change touches the public API or introduces a new dependency.

Thanks for keeping the bar high.
