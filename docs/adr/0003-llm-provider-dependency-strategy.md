# ADR 0003: OpenAI-compatible first LLM clients on JDK HttpClient — no vendor SDKs

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

DocTruth needs to work with the model APIs Java teams already use. In practice,
the most portable integration surface is OpenAI-compatible chat completions:
OpenAI itself, many hosted model gateways, and several vendor-compatible APIs
share the same request/response shape. Anthropic and Gemini still deserve native
providers because their structured-output semantics are different enough to be
worth modeling directly.

The question is whether to depend on vendor SDKs, hand-roll thin clients on JDK
`java.net.http.HttpClient` + Jackson, or use a hybrid approach.

Current provider surfaces:

- `OpenAiProvider` for OpenAI and compatible chat-completions endpoints.
- `AnthropicProvider` for Anthropic Messages API and tool-use forcing.
- `GeminiProvider` for Gemini JSON response mode and response schemas.
- `DeepSeekProvider` as a convenience adapter for a common OpenAI-compatible endpoint.

Relevant SDKs:

- `com.openai:openai-java` (OpenAI, substantial transitive surface)
- `com.anthropic:anthropic-java` (Anthropic, ~10 transitive deps including OkHttp + Kotlin
  stdlib + Jackson, > 5 MB on classpath)
- `com.google.cloud:google-cloud-vertexai` (Gemini via Vertex, > 30 transitive deps)
- Many OpenAI-compatible endpoints have no first-party Java SDK.

## Decision

**Hand-roll thin provider clients, OpenAI-compatible first.** Provider HTTP
clients go through `internal.providers.*` and are built on:

- `java.net.http.HttpClient` (JDK 25 baseline, no extra deps)
- `com.fasterxml.jackson.core:jackson-databind` (already a direct dep — used for record ↔ JSON)
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (already direct — `Instant` support)
- `dev.failsafe:failsafe` for retry / backoff (per ADR 0004)

We use **no vendor SDK**. Provider-specific wire shapes are encoded as Java
records under `ai.doctruth.internal.providers.<vendor>.wire` and serialised via
Jackson. New model endpoints should use `OpenAiProvider` with a custom endpoint
and model unless they require genuinely different structured-output behavior.

## Consequences

### Why hand-rolling wins for *this* library

- **Single-jar < 500 KB.** The official SDKs would push transitive footprint
  over 30 MB. The metric is on the *published* jar, but the on-classpath cost is what users
  feel — large transitive trees create version-conflict resolution work for every consumer.
- **No coupling to vendor SDK version churn.** `anthropic-java` is at 0.x and ships breaking
  changes monthly; coupling our public API to a 0.x library would force a major bump on us
  every time Anthropic refactored. Hand-rolled wire records under `internal.*` insulate
  the public surface.
- **The contribution rules recommend `java.net.http.HttpClient`** over OkHttp /
  Apache HttpClient; the official SDKs bundle one of those.
- **Each provider's API surface we use is small.** Chat completions with JSON Schema,
  Anthropic Messages with tool-use forcing, and Gemini JSON mode are small enough to
  encode as explicit wire records instead of bringing in generated SDK surfaces for
  endpoints DocTruth never calls.
- **Decoupling (§1).** Our public `LlmProvider` sealed interface MUST NOT leak vendor SDK
  types (`anthropic.MessageRequest` etc.) — otherwise swapping providers would be a public
  API break. Hand-rolling is the obvious enforcement mechanism.

### What hand-rolling costs

- **Manual sync to provider API changes.** When OpenAI-compatible structured output,
  Anthropic tool-use, or Gemini response schemas change, we have to update our wire
  records. Mitigation: keep wire types under `ai.doctruth.internal.providers.*` so
  changes do not break the public API.
- **Manual SSE / streaming parsing.** Streaming will need a thin SSE reader; JDK
  `HttpClient` exposes streaming response bodies via `BodyHandlers.ofLines()`, so this is a
  ~30 LOC adapter, not a re-implementation.
- **No first-class observability hooks.** SDKs sometimes ship OpenTelemetry instrumentation;
  we provide our own SLF4J events at boundaries (per CONTRIBUTING.md §2).

### Revisit triggers

This ADR is revisited if any of the following:

1. A provider ships a feature whose wire format is too complex to maintain
   (e.g., binary tool-use protocol).
2. A first-party SDK becomes lightweight enough (single-jar, no transitive deps) that the
   coupling-cost trade-off flips. Currently none qualify.
3. A provider-specific maintainer takes ownership of one surface and can justify the
   SDK trade-off in a follow-up ADR.

## Alternatives considered

- **Vendor SDKs for OpenAI / Anthropic / Gemini.** Rejected for
  jar size, transitive-dep coupling, and version churn. Specifically rejected: pulling four
  vendor SDKs whose internal HTTP libraries (OkHttp, Apache HttpClient) duplicate the JDK's
  built-in `HttpClient`.
- **LangChain4j.** Brings the entire framework — exactly what DocTruth exists *not*
  to be (per CONTRIBUTING.md scope boundaries).
- **Spring AI's `ChatClient`.** Spring-coupled; the library is intentionally framework-free.
- **Hybrid (wrap one SDK, hand-roll others).** Inconsistent reader experience and split test
  surface. Rejected.

## Implementation note

`OpenAiProvider` is the default reference for new model integrations because
compatible endpoints are common and require only endpoint/model configuration.
`AnthropicProvider` and `GeminiProvider` remain native implementations where the
wire semantics differ. Each provider's HTTP code lives in
`ai.doctruth.internal.providers.<vendor>.HttpClient` and its wire shapes in
`ai.doctruth.internal.providers.<vendor>.wire.*` records.
