# ADR 0008: Apache License 2.0 and DocTruth trademark policy

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

DocTruth is an open-source Java library intended for production document
extraction pipelines. The license needs to be familiar to Java teams, friendly
to internal legal review, and explicit about patent rights.

The project also needs a clear brand policy. The Apache License grants broad
rights to use, modify, and redistribute the code. It does not grant permission
to use the project name or logo for a fork or unrelated distribution.

## Decision

1. **Code license**: Apache License 2.0.
2. **Brand policy**: `DocTruth`, `doctruth.ai`, and the DocTruth logo are
   project trademarks controlled by doctruthhq.
3. **NOTICE file**: every source and binary release includes the trademark
   notice and third-party dependency summary required by Apache 2.0.

## Consequences

- Users can adopt, audit, modify, and redistribute the code under a standard
  OSI-approved license with an explicit patent grant.
- Forks are allowed, but they must use a different name and logo.
- Contributors and downstream users can understand the code-vs-brand boundary
  without reading any private project plan.

## Alternatives Considered

- **MIT.** Simpler, but lacks Apache 2.0's explicit patent grant.
- **Copyleft or source-available licenses.** Rejected because DocTruth should
  remain a permissive open-source Java library.
- **No trademark notice.** Rejected because the project name should stay
  unambiguous even though the code is permissively licensed.
