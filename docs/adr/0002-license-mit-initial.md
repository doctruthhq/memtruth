# ADR 0002: License MIT for v0.x; revisit at v1.0

- **Status**: ⚠️ **Superseded by [ADR 0008](0008-license-apache-2-0-and-trademark.md)** (same day)
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

> This ADR documented the alpha-period choice of MIT. It was superseded the
> same day by ADR 0008, which switches the project to Apache License 2.0 and
> records the `doctruth` trademark policy. Kept here as historical record.

## Context

`doctruth-java` is a public open-source Java library with three strategic
audiences:

1. Hobbyist / individual developers experimenting with auditable LLM extraction.
2. Enterprise Java teams in regulated industries (the primary v1.0+ target).
3. a downstream production system itself (the maintainer's company), which may bundle this library in
   commercial products distributed to paying customers.

Two licenses dominate this niche:

- **MIT** — short, permissive, no patent grant.
- **Apache License 2.0** — permissive with explicit patent grant; preferred by
  enterprise procurement / legal.

## Decision

Release as **MIT** for the entire `v0.x` series. Re-evaluate before tagging
`v1.0.0`. If any of the following are true at that point, switch to **Apache
2.0**:

- Any enterprise prospect blocks adoption explicitly on patent-grant absence.
- A contributor lands a non-trivial PR (≥ 50 LOC, novel algorithm) — patent
  grant becomes their interest, not just ours.
- We add native code (FFM, JNI) where patent risk is non-zero.

## Consequences

### Why MIT for v0.x

- **Lower friction for the alpha-period contributor / user pool.** Hobbyists
  and indie devs don't read CLA / patent clauses; MIT is "obvious".
- **Easier dual-licensing later.** MIT → Apache 2.0 is a simple relicense (all
  contributors implicitly opt-in by accepting MIT); the reverse is harder.
- **Matches the "small library" tone.** Apache 2.0 boilerplate (the NOTICE
  file, the long header) signals "enterprise framework"; MIT signals "small
  composable primitive" — the latter is the brand we want at v0.x.

### What MIT costs us

- **No explicit patent grant.** A contributor (or a maintainer) could in theory sue a
  user for patent infringement on contributed code. Mitigation: MIT does have
  an *implicit* patent grant under most case law, and we are not aware of any
  patent claims in this space.
- **Some enterprise legal teams reject MIT outright** for libraries containing
  novel algorithms. Mitigation: contact those teams; offer Apache 2.0 dual-
  license on request, free of charge.

### Revisit triggers

A formal revisit before `v1.0.0` will:

1. Survey the top 20 production users (if known) for license preference.
2. Audit the contributor list and confirm with each non-trivial contributor
   whether relicensing to Apache 2.0 is acceptable.
3. Update this ADR with the final decision.

## Alternatives considered

- **Apache License 2.0 from day 0.** Rejected for v0.x because the patent-
  grant value is hypothetical until enterprise users surface; alpha-period
  friction matters more.
- **BSD-3-Clause.** Very similar to MIT; no meaningful difference for our use.
- **MPL 2.0 / LGPL.** File-level / library-level copyleft; too restrictive for
  a primitive library that wants to be embedded inside closed-source products.
- **Dual-license MIT + Apache 2.0 from day 0.** Considered; the dual-license
  boilerplate adds cognitive overhead for first-time contributors. Defer to
  v1.0+ if needed.
