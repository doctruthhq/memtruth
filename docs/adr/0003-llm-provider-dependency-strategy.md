# ADR 0003: Hand-rolled thin LLM clients on JDK HttpClient — no vendor SDKs

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

`doctruth-java` ships four `LlmProvider` implementations (Anthropic, OpenAI, Gemini,
DeepSeek). Each provider has an official Java SDK:

- `com.anthropic:anthropic-java` (Anthropic, ~10 transitive deps including OkHttp + Kotlin
  stdlib + Jackson, > 5 MB on classpath)
- `com.openai:openai-java` (OpenAI, similar shape)
- `com.google.cloud:google-cloud-vertexai` (Gemini via Vertex, > 30 transitive deps)
- DeepSeek currently has no first-party Java SDK

The question is whether to depend on these SDKs, hand-roll thin clients on JDK
`java.net.http.HttpClient` + Jackson, or hybrid.

## Decision

**Hand-roll thin per-provider clients.** All four providers go through `internal.providers.*`
HTTP clients built on:

- `java.net.http.HttpClient` (JDK 25 baseline, no extra deps)
- `com.fasterxml.jackson.core:jackson-databind` (already a direct dep — used for record ↔ JSON)
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (already direct — `Instant` support)
- `dev.failsafe:failsafe` for retry / backoff (per ADR 0004)

We use **no vendor SDK**. Provider-specific wire shapes are encoded as Java records under
`ai.doctruth.internal.providers.<vendor>.wire` and serialised via Jackson.

## Consequences

### Why hand-rolling wins for *this* library

- **AGENTS.md single-jar < 500 KB.** The four official SDKs would push transitive footprint
  over 30 MB. The metric is on the *published* jar, but the on-classpath cost is what users
  feel — large transitive trees create version-conflict resolution work for every consumer.
- **No coupling to vendor SDK version churn.** `anthropic-java` is at 0.x and ships breaking
  changes monthly; coupling our public API to a 0.x library would force a major bump on us
  every time Anthropic refactored. Hand-rolled wire records under `internal.*` insulate
  the public surface.
- **AGENTS.md "Engineering principles" §4** explicitly recommends `java.net.http.HttpClient`
  over OkHttp / Apache HttpClient; the official SDKs bundle one of those.
- **Each provider's API surface we use is small.** `messages` endpoint with tool-use
  forcing the schema, plus streaming for Phase 4+. ~150 lines of HTTP per provider, vs the
  SDKs' tens of thousands of generated lines for endpoints we never call.
- **Decoupling (§1).** Our public `LlmProvider` sealed interface MUST NOT leak vendor SDK
  types (`anthropic.MessageRequest` etc.) — otherwise swapping providers would be a public
  API break. Hand-rolling is the obvious enforcement mechanism.

### What hand-rolling costs

- **Manual sync to provider API changes.** When Anthropic ships a new tool-use shape or a
  new prompt-cache header, we have to update our wire records. Mitigation: keep wire types
  under `ai.doctruth.internal.providers.*` so changes don't break the public API; ship
  patch releases (`v0.x.y+1`) within 7 days of upstream changes (per the release policy).
- **Manual SSE / streaming parsing.** Phase 4+ streaming will need a thin SSE reader; JDK
  `HttpClient` exposes streaming response bodies via `BodyHandlers.ofLines()`, so this is a
  ~30 LOC adapter, not a re-implementation.
- **No first-class observability hooks.** SDKs sometimes ship OpenTelemetry instrumentation;
  we provide our own SLF4J events at boundaries (per AGENTS.md §2).

### Revisit triggers

This ADR is revisited if any of the following:

1. A provider ships a feature whose wire format is too complex to maintain
   (e.g., binary tool-use protocol).
2. A first-party SDK becomes lightweight enough (single-jar, no transitive deps) that the
   coupling-cost trade-off flips. Currently none qualify.
3. We hire a maintainer specifically for one provider's surface and they prefer the SDK.

## Alternatives considered

- **`com.anthropic:anthropic-java` + similar for OpenAI / Gemini / DeepSeek.** Rejected for
  jar size, transitive-dep coupling, and version churn. Specifically rejected: pulling four
  vendor SDKs whose internal HTTP libraries (OkHttp, Apache HttpClient) duplicate the JDK's
  built-in `HttpClient`.
- **LangChain4j.** Brings the entire framework — exactly what `doctruth-java` exists *not*
  to be (per AGENTS.md "What this project is NOT").
- **Spring AI's `ChatClient`.** Spring-coupled; the library is intentionally framework-free.
- **Hybrid (wrap one SDK, hand-roll others).** Inconsistent reader experience and split test
  surface. Rejected.

## Implementation note

The first hand-rolled provider (`AnthropicProvider`, Phase 1) is the canonical reference;
the other three are written by analogy with provider-specific wire records. Each provider's
HTTP code lives in `ai.doctruth.internal.providers.<vendor>.HttpClient` (≤ 200 LOC) and
its wire shapes in `ai.doctruth.internal.providers.<vendor>.wire.*` records.
