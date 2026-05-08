# ADR 0008: Switch to Apache License 2.0 + register `doctruth` as a trademark

- **Status**: Accepted (supersedes ADR 0002)
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

ADR 0002 picked MIT for v0.x with the intent to "revisit before v1.0". Two
factors moved that revisit forward:

1. **Patent grant matters for the buyer profile.** Our v1.0 target customers
   are Java engineers in regulated industries (banks, insurers, healthcare,
   construction, government). Their legal teams have OSS allow-lists.
   Apache 2.0 is on every such list and is preferred for its explicit patent
   grant; MIT is also allowed but treated as inferior because it has no
   patent clause.

2. **The "anti-cloud" question came up earlier than expected.** We considered
   anti-competition source-available licences (BSL-1.1, AGPL-3.0, SSPL,
   Elastic v2, PolyForm Shield). Each of those is OSI-non-approved or
   widely-banned by enterprise procurement, which would close exactly the
   buyer doors we're trying to open. The competitive moat we *can* defend is
   not the code — it's the brand and operations: the `doctruth.ai` hosted
   service, the SOC 2 / ISO 27001 posture, the regulatory contract packs,
   domain knowledge, and the maintainer-led roadmap.
   None of those are protected by a copyright licence; they are protected by
   trademark, trade secret, and execution speed.

So the decision is **Apache 2.0 for the code + trademark for the brand**, not
BSL/AGPL/SSPL.

## Decision

1. **Code licence**: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
   Replaces the previous MIT licence on the entire `doctruth-java` repository
   from this commit forward. The MIT-licensed historical commits remain
   available under MIT (one-way licence change at a single point in time);
   future contributions are under Apache 2.0 as part of the implicit grant in
   §5 of the Apache 2.0 text.
2. **Trademark**: register `doctruth` and the `doctruth.ai` wordmark with
   IP Australia (Class 9 — software / Class 42 — SaaS) as the canonical
   moat-bearing asset. Trademark application is the maintainer's
   responsibility; it does NOT block code releases.
3. **NOTICE file** distributed with every source and binary release, stating
   the trademark policy explicitly: forks and downstream distributions are
   permitted by the Apache 2.0 grant, but they must NOT use the `doctruth`
   name or logo to market themselves.

## Consequences

### Why this combination wins

- **Apache 2.0 is the lingua franca of enterprise-friendly open source.**
  Procurement reviews are essentially automatic; legal teams accept it
  without redlines. Banks / insurers have allow-listed it for decades.
- **Patent grant.** Apache 2.0 §3 gives an explicit, irrevocable patent
  licence; this matters for users worried about patent assertion by
  contributors. MIT lacks this.
- **Trademark, not copyleft, is the moat.** Anyone can fork the source and
  ship a competing JAR (that's the Apache 2.0 freedom we wanted), but they
  cannot label it `doctruth-…` or use the logo. Their fork has zero brand
  equity, so the cloning attack pays poorly.
- **Future "competing-service" defence is preserved by ops + speed**, not by
  licence. The licence gives away the code; the maintainer keeps the brand,
  the hosted service, the contract packs, the domain expertise, and the
  ability to ship a feature faster than a fork can be productionised.
- **Compatibility with the open-core SPI strategy** (ADR 0006). Apache 2.0
  on the OSS layer remains compatible with a separate proprietary licence on
  the `doctruth-enterprise` commercial JAR — no contamination.

### What it costs

- **AWS / Anthropic / Reducto can fork the code.** Mitigation: they have to
  rebrand, relaunch, recertify (SOC 2 / HIPAA), and resell — a much harder
  position than copy-pasting a SaaS endpoint. They also lose the case studies
  and the maintainer-driven cadence.
- **Licence change history is one-way.** From this point forward, all
  contributions are Apache 2.0; the prior MIT commits stay MIT (anyone can
  fork them under MIT). In practice that exposed window is narrow because the
  MIT-era repo was never pushed to a public remote; only the local copy
  exists, and the next push will be under Apache 2.0.
- **Trademark requires actual filing + monitoring.** Maintainer must submit
  the IP Australia application (Class 9 + 42, ≈ A\$330) and monitor for
  infringement. Without filing, the trademark right is weaker (common-law
  only) and harder to enforce overseas.

### Revisit triggers

This ADR is revisited if any of the following happens:

1. A direct cloud competitor launches a `doctruth.ai` clone within 90 days of
   our public release (i.e. they will have to do this anyway under any
   permissive licence; the question is whether trademark policy is enough to
   protect the brand). At that point, evaluate whether to add a BSL-1.1
   sub-clause for new contributions only.
2. We hire a contributor whose employer requires a stricter copyleft
   contribution agreement. Apache 2.0 supports DCO + ICLA workflows.
3. v2.0 or beyond — at scale, dual-licensing (Apache 2.0 + commercial) for
   strict-warranty customers may become attractive. Apache 2.0 is compatible
   with that move.

## Alternatives considered

- **MIT (status quo per ADR 0002).** Accepted as the alpha licence; superseded
  by this ADR. MIT's lack of patent clause is the main deciding factor; banks
  prefer Apache 2.0.
- **BSL-1.1 + 4-year change date.** Considered for the anti-SaaS-clone angle.
  Rejected because BSL is not OSI-approved and is auto-banned by many
  enterprise procurement teams — closing the doors of our actual buyers.
- **AGPL-3.0.** Considered for the anti-cloud copyleft. Rejected because
  Google's internal AGPL ban list and similar enterprise blocks would also
  close our buyer doors. The protection it offers is real (AWS won't ship
  AGPL software in a hosted service) but the side effect is too costly here.
- **SSPL.** Rejected outright; the surrounding-software disclosure
  requirement is a procurement nuclear button.
- **Elastic License v2.** Rejected; the explicit non-OSI status is a hard
  block in regulated sectors.
- **PolyForm Shield 1.0.** Cleaner anti-competition language than BSL but
  even less battle-tested. Rejected for the alpha; revisit if future
  conditions change.
- **Commons Clause + MIT.** Adds a "no resale" clause on top of MIT.
  Non-OSI; community pushback common; provides weaker anti-cloud teeth than
  AGPL. Rejected.
- **Dual-licence Apache 2.0 + Commercial from day 0.** Considered. Defer
  until v1.0+: at this stage, simply being permissive maximises the size of
  the install base that we can later upsell to commercial-tier features (per
  ADR 0006).

## Implementation note

Effective immediately:

1. `LICENSE` is replaced with the canonical Apache 2.0 text plus the standard
   appendix and a 2026 copyright line for doctruthhq maintainers.
2. `NOTICE` is added at the repository root with the trademark statement and
   a third-party-libraries summary (per Apache 2.0 §4(d)).
3. `pom.xml` `<licenses>` block is updated to point at Apache 2.0.
4. The `<comments>` field of that licence block records the trademark
   carve-out so anyone reading the published Maven Central POM understands
   the brand-vs-code split without having to open the NOTICE file.
5. README, CHANGELOG, AGENTS.md, AGENTS.md, CONTRIBUTING.md, and `docs/release.md`
   are updated to reference Apache 2.0 instead of MIT.
6. ADR 0002 is preserved as historical record (it documented the alpha
   decision) and now carries a `Status: Superseded by ADR 0008` note.

The repository has not yet been pushed to a public remote, so the MIT-era
window is the local Git history only. Future contributors signing the Apache
2.0 contribution clause (per §5) cleanly inherit the new terms.
