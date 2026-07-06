# ADR 0012: Initial Memtruth Repository Rename Direction

## Status

Superseded by [ADR 0013](0013-memtruth-sdk-server-boundary.md), 2026-07-06.

## Context

DocTruth has the stronger existing OSS repository surface, while Memtruth is the
broader product direction: local-first agent memory, evidence, trust ledger,
context packs, MCP access, and replay. Maintaining DocTruth and Memtruth as
separate repos has already created copy drift. The legal Vespa implementation
contains a newer `memtruth-sdk` than the standalone Memtruth checkout.

The combined project needs one public repository identity without losing the
clear product boundary that made DocTruth useful:

```text
Memtruth        top-level product, SDK, contracts, memory, trust, replay
Memtruth Parse  document evidence and parsing module, formerly DocTruth
Infer Cloud     hosted enterprise control plane
```

## Decision

Use the current `doctruthhq/DocTruth` repository as the migration target and
rename the repository direction to Memtruth. DocTruth becomes the parse/document
evidence module inside the Memtruth repository identity.

The existing `doctruth` Java package, Maven coordinate, CLI command, runtime
binary, and audit contracts remain compatibility surfaces during the migration.
New product-level positioning should use Memtruth and Memtruth Parse.

Import the newer legal Vespa `memtruth-sdk` into this repository as an
independent Rust workspace at `memtruth-sdk/`. That workspace owns corpus
contracts, section-aware chunking, retrieval projection, and Vespa feed/preflight
diagnostics. It must not depend on parser-private Java or Rust implementation
details.

## Boundaries

Parser/runtime behavior remains Rust-owned under `runtime/doctruth-runtime`.
Java remains wrapper, packaging, lifecycle, API, and CLI compatibility.

`memtruth-sdk` owns retrieval-facing contracts and projections:

```text
source corpus -> Memtruth contracts -> chunker -> projector -> search adapters
```

The parser-to-memory bridge should be explicit:

```text
document -> TrustDocument -> Source/EvidenceSpan/Claim inputs -> Memory/ContextPack/Replay
```

Do not make Memtruth memory, MCP, Vespa, or hosted modules call parser-private
types directly. Use stable contract adapters.

## Supersession Note

This ADR's broad monorepo direction was narrowed by
[ADR 0013](0013-memtruth-sdk-server-boundary.md). The accepted direction is now:

```text
this repository = Memtruth SDK main repository
memtruth-server = memory, MCP, storage, replay, and server runtime
```

Do not use this ADR as authorization to import the old Memtruth memory layer,
MCP runtime, storage implementation, replay service, server process, or
embedding worker into this SDK repository.

## Consequences

The OSS entry point becomes broader and closer to the real product direction:
memory is the product, auditability is the moat, and parsing is the first
evidence ingestion surface.

The migration must be staged. A single hard rename would break existing DocTruth
users, Maven coordinates, scripts, and parser-runtime checks. Compatibility
aliases stay until a documented breaking release.
