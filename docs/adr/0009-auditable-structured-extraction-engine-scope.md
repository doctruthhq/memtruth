# ADR 0009: DocTruth owns schema-bound auditable structured extraction

- **Status**: Accepted
- **Date**: 2026-05-08
- **Deciders**: doctruthhq maintainers

## Context

The first implementation wave proved the low-level evidence path: document
parsers, citations, confidence, provenance, provider clients, audit export, and
custom field/object constraints.

The remaining product question is whether DocTruth should stop at
parser/evidence primitives or include Instructor/Pydantic-class structured
extraction behavior.

Instructor's useful contract is:

```text
LLM response -> schema validation -> retry -> typed object
```

Pydantic's useful contract is:

```text
data -> schema/type validation -> custom validators -> typed model
```

DocTruth's differentiator is evidence:

```text
document -> parsed source -> schema-bound extraction -> validation
         -> citation validation -> provenance/audit output
```

If DocTruth excludes schema-bound extraction and custom constraints, users
still need a separate Instructor-like layer and DocTruth becomes only a parser
plus citation helper. That is weaker than the project purpose.

## Decision

DocTruth will own **auditable structured extraction** as an open-source core capability.

The engine scope includes:

1. Document parsing.
2. Evidence location.
3. Schema-bound extraction.
4. Validation and repair.
5. Evidence-gated output.
6. Audit export.

DocTruth will not become a Java clone of Pydantic and will not depend on Python
Instructor. Instead:

- Java records/classes are the native typed-object path.
- JSON Schema is the schema interchange format.
- Pydantic compatibility means importing Pydantic-generated JSON Schema,
  including common v2 constructs such as local `$defs` / `$ref`, nullable
  `anyOf`, `oneOf` / `allOf`, type arrays, nested properties, and basic scalar
  / array constraints.
- Provider-specific wrappers are internal strategies behind a provider-agnostic
  public API.

The current custom constraint API is the first accepted slice of this decision:

```java
.withFieldConstraint("totalValue", BigDecimal.class, value -> value.signum() > 0, "must be positive")
.withObjectConstraint(contract -> !contract.partyA().equals(contract.partyB()), "partyA and partyB must differ")
```

## Project boundary

The structured extraction engine should stay fully useful as a local Java
library. Domain-specific applications can build on top of it, but they should
not become generic core behavior.

| Capability | Boundary | Rationale |
| --- | --- | --- |
| Java record/class schema extraction | In core | Core Java developer experience. |
| JSON Schema input | In core | Core interoperability primitive. |
| Imported Pydantic JSON Schema | In core | Compatibility with existing Python contracts. |
| Type/enum/required validation | In core | Baseline structured extraction behavior. |
| Field/object constraints | In core | Core reliability and business-rule primitive. |
| Retry/repair engine | In core | Required for an Instructor-class replacement. |
| Citation requirement policies | In core | Core DocTruth differentiator. |
| Provider structured-output wrappers | In core | Needed for the engine to work without framework lock-in. |
| Audit JSON export | In core | Core auditability primitive. |
| Domain schemas | Out of core | Resume, contract, medical, insurance, and procurement schemas belong to applications. |
| Jurisdiction-specific interpretation | Out of core | Legal/regulatory interpretation changes over time and should be owned by domain packages or applications. |
| SIEM, key-management, and residency integrations | Out of core | Organization-specific deployment policy. |
| Dashboard / auditor portal | Out of core | Application surface beyond the library. |
| OCR engines and form-recognition models | Out of core by default | Heavy model/runtime choices should be pluggable rather than bundled. |

## Consequences

### Positive

- The project can credibly claim more than parser/evidence support.
- Java users get an Instructor-class structured-output layer without adopting a
  chain framework.
- Python/Pydantic teams can interoperate through JSON Schema without putting
  Python into Java production services.
- Packaging remains honest: the generic library is complete for auditable
  extraction, and deployment or organization-specific integrations stay outside
  the generic extraction loop.

### Costs

- JSON Schema validation becomes a core design surface and may require another
  dependency ADR if current Jackson schema support is insufficient.
- Provider wrappers must normalize different structured-output modes without
  leaking provider-specific concepts into the public API.
- Repair prompts must be carefully tested to avoid hiding invalid output behind
  optimistic retries.
- Citation requirements need policy design: fail, warn, degrade, or return for
  manual review.

### Non-goals

- No business-domain schemas in core.
- No vector store abstraction.
- No agent framework.
- No Spring-specific API.
- No Python runtime dependency.
- No claim that LLM output can be made perfectly deterministic.

## Implementation sequence

1. Custom constraints with stable error codes and retry semantics.
2. JSON Schema input and local validation.
3. Citation requirement policies for selected field paths.
4. Repair/retry prompts that include validation and citation errors.
5. Provider-specific structured-output strategies under the existing provider
   abstraction.
6. Audit output that records validation errors, repair attempts, and citation
   policy outcomes.

## Revisit triggers

1. A major Java framework ships a mature, provider-agnostic structured
   extraction layer with citation gating.
2. JSON Schema support forces a dependency that makes the jar exceed the size
   budget without clear user value.
3. Users ask to add domain behavior to the generic core; default answer is no
   unless the behavior is truly generic evidence, validation, or provenance
   infrastructure.

## Alternatives considered

- **Parser/evidence only.** Rejected: leaves users needing Instructor-like
  orchestration elsewhere and weakens DocTruth's product identity.
- **Java Pydantic clone.** Rejected: wrong abstraction for Java and too much
  runtime surface.
- **Python Instructor bridge.** Rejected: cross-language dependency weakens the
  Java deployment story.
- **Keep the structured extraction engine incomplete.** Rejected: an incomplete
  core will not win developer trust. Applications and organization-specific
  integrations can sit outside the library without weakening the generic engine.
