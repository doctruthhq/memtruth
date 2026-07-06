# ADR 0013: Keep Memtruth SDK Separate from Memtruth Server

## Status

Accepted, 2026-07-06.

## Context

ADR 0012 started the migration from the public DocTruth repository toward a
Memtruth repository identity. After that first migration slice, the product
boundary was clarified: the public repository with the existing OSS visibility
should become the Memtruth SDK main repository, not the full memory/server
monorepo.

The older Memtruth checkout contains useful memory-layer implementation:
storage, MCP runtime, server process, replay runtime, embedding worker, and
local dogfood operations. Those components are not SDK surface area and should
not be imported wholesale into this repository.

## Decision

This repository is the Memtruth SDK main repository.

It owns:

```text
Memtruth SDK
Memtruth Parse, formerly DocTruth
document evidence and parsing
Java compatibility SDK and CLI surfaces
Rust corpus/source/chunker/projector contracts
retrieval projection and Vespa preflight diagnostics
local developer examples and SDK documentation
```

The memory layer belongs in a separate `memtruth-server` line.

It owns:

```text
long-term memory storage
MCP runtime/server
context pack runtime
replay and action ledger services
Pglite/Redis storage implementations
embedding worker and server processes
production/dogfood operations
```

Do not import the old Memtruth memory, MCP, storage, replay service, server, or
embedding-worker implementation into this SDK repository unless this decision is
explicitly reversed.

## Compatibility

The current Java package, Maven coordinate, CLI command, release artifact names,
and runtime names remain `doctruth` compatibility surfaces until a staged
breaking release introduces Memtruth aliases and migration tests.

## Consequences

The SDK repository can be renamed and documented as Memtruth without pulling in
the operational memory server. This keeps the OSS entry point small and
developer-friendly while leaving room for `memtruth-server` to evolve as the
memory, MCP, storage, and replay runtime.
