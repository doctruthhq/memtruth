# ADR 0006: Open-core split — OSS library + commercial jar + hosted SaaS

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

`doctruth-java` targets regulated-industry buyers — banking, insurance,
healthcare, construction, government — who need auditable LLM extraction and
will pay for compliance posture. The public release plan also commits to a `Show HN`
open-source launch as the mind-share path.

These two goals create a monetisation triangle:

- **Pure OSS** (Apache 2.0, no paid layer) — mind-share but no business. Enterprise
  prospects ask "who do I sign a support contract with?" and there is no answer.
- **Pure SaaS** (closed-source, hosted-only) — no mind-share and no procurement-
  audit advantage, because regulated buyers often want to review the code their
  auditable pipeline runs on.
- **Open-core** — OSS primitive layer wins mind-share + procurement code
  review; commercial layer monetises ops + compliance + integrations.

The compliance roadmap (maintained in the commercial `doctruth-enterprise`
repository, not in OSS) already lists HMAC signing, region enforcement, and
SIEM listeners as commercial-tier work — those are precisely the features
regulated buyers will pay for, and they are ops/integration concerns rather
than primitives.

**Per-jurisdiction regulatory contract test packs (AU, EU AI Act, HIPAA, SOC 2,
SEC 17a-4, UK FCA) ship in the commercial tier as well.** Although a contract
pack is "just code", maintaining one against an evolving regulator (APRA
gazettes, ASIC RG updates, EU AI Act delegated acts) is an ongoing service
relationship — that maintenance commitment, the SLA on update lag, the
quarterly compliance briefing, and the auditor-facing PDF report are what
regulated buyers actually pay for. This honours the **Open Core first axiom**:
*give away what is cheap to replicate; charge for what is hard to deliver*.

## Decision

Adopt **open-core** with three layers:

1. **OSS library** — Apache 2.0, `ai.doctruth:doctruth-java`, the entire public API +
   all SPI interfaces + default no-op SPI implementations.
2. **Commercial library** — proprietary licence, `ai.doctruth:doctruth-enterprise`,
   paid jar containing rich SPI implementations (signing, region enforcement,
   SIEM push) + bundled support contract.
3. **Hosted SaaS** — `api.doctruth.ai`, multi-tenant managed service for users
   who do not want to self-host.

**Pre-bake all extension points (SPIs) in OSS** so commercial implementations
plug in without any OSS API change.

## OSS scope (free, Apache 2.0)

| Area | Contents |
|---|---|
| Public API | All `ai.doctruth.*` root packages (per AGENTS.md "Public API contracts") |
| LLM providers | Anthropic, OpenAI, Gemini, DeepSeek hand-rolled HTTP clients (per ADR 0003) |
| Citation matching | JaroWinkler via Apache Commons Text (per ADR 0005) |
| Structured extraction | Java record/class schema extraction, JSON Schema input, imported Pydantic JSON Schema, validation, repair/retry, citation policies (per ADR 0009) |
| Smart context | `PriorityTruncate`, `SlidingWindow`, `Hierarchical` strategies |
| Audit export | PROV-O JSON-LD provenance writer |
| Document parsing | PDFBox-backed PDF parser, POI-backed DOCX parser |
| **All SPIs** | `ai.doctruth.spi.*` interfaces — every extension point |
| **All default SPI impls** | No-op / identity defaults so the OSS jar runs standalone |

## Commercial library scope (paid jar)

`ai.doctruth:doctruth-enterprise:<version>` — proprietary licence, sold per
deployment.

| Capability | SPI plugged into |
|---|---|
| `HmacSignatureProvider` (sealed audit JSON) | `SignatureProvider` |
| `Ed25519SignatureProvider` (asymmetric audit signing) | `SignatureProvider` |
| `RegionEnforcingTransport` (AU bank data residency) | `RegionResolver` + transport |
| `SplunkAuditEventListener` | `AuditEventListener` |
| `DatadogAuditEventListener` | `AuditEventListener` |
| `CloudWatchAuditEventListener` | `AuditEventListener` |
| `SumoLogicAuditEventListener` | `AuditEventListener` |
| Maintained regulatory packs (AU, EU AI Act, HIPAA, SOC 2, SEC 17a-4, UK FCA) | commercial compliance-pack API |
| Jurisdiction-specific contract tests and auditor reports | commercial compliance-pack API |
| Premium model selection / prompt-cache optimisation helpers | (helper API) |
| 24×7 SLA support contract | bundled with the jar licence |

## Hosted SaaS scope (`api.doctruth.ai`)

| Capability | Notes |
|---|---|
| Multi-tenant management | Orgs, users, role-based access, key pools |
| Per-call billing | Stripe metered billing |
| Web dashboard | Audit-log inspection, citation viewer, provenance export |
| Bring-your-own-key | Tier 1 |
| Managed keys | Tier 1+, key rotation handled by us |
| SOC 2 Type 2 / HIPAA BAA | Compliance posture, operational not code |
| PSPF posture | AU government tier (data sovereignty + clearance) |

## Pricing tiers

| Tier | Layer | Price | Audience |
|---|---|---|---|
| 0 | OSS, self-host | Free (Apache 2.0) | Hobbyists, indie devs, internal R&D |
| 1 | Hosted self-serve | $29 / $99 / $299 per month | SMB, individual products |
| 2 | Commercial library | $5–30k per year per deployment | Self-hosting enterprises with compliance needs |
| 3 | Hosted enterprise | $50–500k per year | Regulated industry, dedicated tenancy, AU residency, custom SLA |

## Pre-baking strategy

The following SPIs **must exist in OSS at v0.1** so the commercial jar can plug
in without any OSS API change later:

| SPI | Default OSS impl | Commercial impl(s) |
|---|---|---|
| `ai.doctruth.spi.AuditEventListener` | `NOOP` (drops events) | Splunk / Datadog / CloudWatch / Sumo Logic |
| `ai.doctruth.spi.SignatureProvider` | `IDENTITY` (passes audit JSON unchanged) | HMAC-SHA256 / Ed25519 |
| `ai.doctruth.spi.RegionResolver` | returns `Optional.empty()` | AU / EU / US region-aware resolver |
| `ai.doctruth.spi.RateLimiter` (future) | `UNLIMITED` | Hosted SaaS impl with per-tenant quotas |
| `ai.doctruth.spi.KeyPoolProvider` (future) | `STATIC_FROM_ENV` | Hosted SaaS rotating-pool impl |

Discovery via `java.util.ServiceLoader` so adding the commercial jar to the
classpath wires the impls automatically — no OSS code change.

## Consequences

### Why open-core wins

- **Pattern is well-trodden.** HashiCorp Terraform, GitLab Free vs Premium,
  MongoDB pre-SSPL, Confluent Kafka — same shape. OSS earns mind-share +
  procurement-easy code review; commercial captures ops/compliance/
  integration value. Buyers pay for posture, not code bits.
- **Procurement story is clean.** Bank legal teams can read 100% of the OSS
  code path (the audit-critical primitives) and know the commercial jar only
  adds plug-in implementations of well-defined SPIs. No "trust us, the closed
  bits are fine" handwave.
- **Compliance items are naturally commercial.** HMAC signing, region
  enforcement, SIEM push are not primitives — they are integrations. Pricing
  them is fair and aligns with the work involved (vendor relationships,
  on-call, certification audits).

### What it costs

- **Every commercial feature must be designable behind an SPI.** Reduces
  feature velocity slightly because the OSS interface has to be designed
  before the commercial impl can land. Acceptable — it forces clean
  abstractions.
- **Some users will fork the OSS to write their own commercial features.**
  Acceptable; they were never going to be paying customers.
- **Coordination overhead between OSS and commercial release cadences.**
  Mitigated by keeping SPIs stable (semantic-versioned per AGENTS.md "Public
  API contracts") and by releasing the commercial jar against a fixed OSS
  minor version range.

### Revisit triggers

1. LangChain4j (or any Java framework) ships a free competing feature that
   subsumes our commercial differentiation → re-evaluate the OSS / commercial
   line for that feature.
2. Hosted SaaS ARR > 5× the commercial-library ARR by month 12 → consider
   open-sourcing the commercial library entirely and concentrating monetisation
   on hosted (à la Sentry's later move).
3. A major customer (≥ $500k ACV) demands a feature that is OSS today →
   consider reclassifying via dual-licensing rather than closing it outright.

## Alternatives considered

- **Pure OSS, no commercial code (rely on hosted SaaS for revenue).** Rejected:
  many regulated enterprise buyers require self-hosting; commercial library
  tier captures that segment.
- **AGPL / SSPL / BSL on the core** (à la MongoDB SSPL, Elastic ELv2). Rejected
  for v0.x: AU bank legal teams sometimes reject these licences, raising
  adoption friction. Revisit at v1.0 if competitive forks become a problem.
- **Closed-source from day 0.** Rejected: kills mind-share, removes the
  procurement-audit advantage, weakens the OSS procurement story
- **Dual-license Apache 2.0 + Commercial on the same code path** (per-customer
  commercial licence). Rejected: administrative overhead, ambiguous
  contributor IP story. Path-distinct OSS vs commercial is cleaner.
