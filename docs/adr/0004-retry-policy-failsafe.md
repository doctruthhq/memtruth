# ADR 0004: Retry / backoff via Failsafe; no hand-rolled loops

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

A prior production retry pattern used a 3-tier nested retry:

1. Outer loop: `2^attempt × 2 seconds` exponential backoff over 3 attempts on API timeouts.
2. Inner: `instructor`'s built-in `max_retries=3` for schema-validation failures.
3. Plus a separate `tenacity.Retrying` block to log each attempt's exception type.

The Java port needs the equivalent behaviour for at least:

- Transient HTTP failures (5xx, 408, 429 with `Retry-After`, network resets).
- LLM schema-validation failures (output didn't match the requested record shape).
- Provider rate-limit responses (429 + structured retry-after).

The question is whether to hand-roll exponential-backoff loops or pull a community library.

## Decision

Use **Failsafe (`dev.failsafe:failsafe`)** for retry policy, exponential backoff, jitter,
and abort-on-non-retryable. Failsafe is added as a direct dependency at the version
declared in `pom.xml`.

## Consequences

### Why Failsafe wins

- **AGENTS.md "Engineering principles" §4** lists Failsafe explicitly as the preferred
  retry/circuit-breaker library; using it is consistent with the project's codified
  defaults.
- **Single small jar.** Failsafe is one ~250 KB jar with zero transitive dependencies.
  No version-conflict risk.
- **Builder API matches our idiom.** `RetryPolicy.<R>builder().withMaxRetries(3)
  .withBackoff(1, 30, ChronoUnit.SECONDS).withJitter(0.1).abortOn(NonRetryableException.class)
  .build()` reads clearly and slots into our existing builder-record DSL.
- **Hand-rolled exponential backoff is bug-prone.** Off-by-one on `attempt` index, missing
  jitter, missing `interruptedException` handling, missing abort-conditions — a prior system's
  retry block had two of these (no jitter; no abort-on-401). Pulling a tested library
  removes a known failure mode.
- **Fits "Build, don't synthesize" §4.** This is the canonical case the principle was
  written for.

### What Failsafe costs

- **One new direct dependency** (was zero before this ADR; was four when counting
  Jackson + PDFBox + POI + SLF4J as already-direct).
- **Version pin we have to update** with every release. Mitigation: Failsafe is at 3.x,
  stable since 2022; minor bumps are non-breaking.
- **Slight learning curve** for contributors unfamiliar with the library. Mitigation:
  retry logic is centralised in `ai.doctruth.internal.retry.*` (single file, ≤ 100 LOC) so
  contributors don't need Failsafe knowledge to read provider code.

### Revisit triggers

1. We need circuit-breaker / bulkhead / rate-limit semantics — at that point evaluate
   Resilience4j (which subsumes Failsafe but is heavier).
2. We need distributed retry coordination (across replicas) — not in scope for v1.0.
3. Failsafe goes unmaintained for > 12 months.

## Alternatives considered

- **Resilience4j (`io.github.resilience4j:resilience4j-retry`).** More features (circuit
  breaker, bulkhead, rate limiter, time limiter, cache) but multiple jars + Vavr transitive
  dep. Overkill for v0.1.0 — revisit at v1.0 if circuit-breaking becomes needed.
- **Spring Retry (`org.springframework.retry`).** Spring-coupled; the library is
  intentionally framework-free.
- **Hand-rolled `for (int i = 0; i < n; i++)` loops with `Thread.sleep(1L << i * 1000)`.**
  Tempting (zero deps) but fails the §4 "Build, don't synthesize" principle. Specific
  pitfalls already seen in a prior system's port:
  - Missing jitter → thundering-herd retries when the upstream blip is correlated.
  - Missing abort condition → retrying on permanent 401 / 403 wastes cost.
  - Missing `InterruptedException` propagation → masks shutdown signals.
  - Off-by-one on the cumulative-delay calculation.
- **JDK 25 `StructuredTaskScope` for retries.** `StructuredTaskScope` is for
  fork-join parallelism, not sequential retry; wrong tool.
- **JDK `CompletableFuture::handle` chains.** Possible (compose retries via recursion) but
  produces unreadable code and mixes async semantics with sync API; rejected.

## Implementation note

A single internal helper (`ai.doctruth.internal.retry.RetryGate`, ≤ 100 LOC) wraps
Failsafe's `Failsafe.with(retryPolicy).get(supplier)` and adds:

- SLF4J emit at `warn` for each retry attempt with `attempt`, `cause.class`, `nextDelay`.
- Translation of `FailsafeException` → `ProviderException` with `retryable=true` /
  `retryable=false` based on the abort policy, so callers see only our public exception type
  (per AGENTS.md §1 decoupling — Failsafe types must not leak through the public API).
- A pluggable `Predicate<Throwable> isRetryable` so each provider can declare
  its own retryable condition without duplicating retry plumbing.
