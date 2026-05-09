# Auditable structured extraction engine

- **Status**: Draft architecture direction
- **Date**: 2026-05-08
- **Owner**: doctruthhq maintainers
- **Related ADRs**: [ADR 0003](../adr/0003-llm-provider-dependency-strategy.md),
  [ADR 0005](../adr/0005-fuzzy-citation-matching-via-commons-text.md),
  [ADR 0009](../adr/0009-auditable-structured-extraction-engine-scope.md)

## Positioning

DocTruth should not stop at document parsing and evidence matching. The
project direction is:

> **DocTruth = auditable structured extraction engine**

That means the library owns the full extraction boundary:

```text
document -> parsed evidence map -> schema-bound LLM extraction
         -> validation and repair -> citation validation
         -> typed value + audit JSON
```

This is deliberately broader than a parser and deliberately narrower than a
general AI framework.

## What this is not

DocTruth should not become any of these:

| Not this | Reason |
| --- | --- |
| Java clone of Pydantic | Java already has records, Bean Validation, Jackson, and JSON Schema tooling. Rebuilding Python's data-model runtime would be the wrong abstraction. |
| Wrapper around Python Instructor | Adds a cross-language runtime dependency and weakens the Java deployment story. |
| LangChain4j or Spring AI replacement | General chains, agents, tools, vector stores, memory, and app orchestration are outside the evidence/provenance boundary. |
| Domain schema library | Resume, contract, medical, insurance, and procurement schemas belong to callers or optional packs, not the generic core. |

The useful lesson from Instructor and Pydantic is the contract:

```text
LLM response -> schema validation -> retry/repair -> typed object
```

DocTruth extends that contract with source evidence:

```text
LLM response -> schema validation -> custom constraints -> citation validation
             -> retry/repair -> typed object + evidence + provenance
```

## Engine layers

### 1. Document parsing

Convert source files into stable, source-located sections:

- PDF / DOCX / XLSX / CSV as current OSS formats.
- Source location with page, line, and character offset.
- Block kind where available: heading, body, list, table, figure.
- Parser failures carry stable `ParseException#errorCode()`.

### 2. Evidence location

Map extracted values back to the parsed document:

- Exact quote where possible.
- Source location for the best matching span.
- Match score surfaced even when low.
- Warning on weak evidence instead of silent omission.
- Optional fail/warn/degrade policy for required citations.

### 3. Schema-bound extraction

DocTruth should accept schemas from multiple sources:

| Schema source | Core behavior |
| --- | --- |
| Java record/class | Native path: generate JSON Schema, validate provider JSON locally, then deserialize to the typed Java value. |
| JSON Schema | Send schema to model provider when supported, validate returned JSON locally. |
| Imported Pydantic JSON Schema | Treat it as standard JSON Schema; do not depend on Python or Pydantic at runtime. |

Provider wrappers are implementation details for this layer:

- OpenAI-compatible structured output / JSON mode as the primary integration path.
- Anthropic tool-use forcing where native Anthropic semantics are useful.
- Gemini JSON response mode where native Gemini semantics are useful.
- Prompt-plus-parse fallback when native structured output is not strong enough.

The public contract should remain provider-agnostic.

Java-native schema generation follows Java and Jackson conventions rather than a
private data-model runtime:

- Records and simple POJOs become `type: object` schemas with named properties.
- Nested records/classes are represented as nested object schemas.
- `List<T>` becomes an array schema, and `Map<String, T>` becomes an object with
  `additionalProperties` set to the value schema.
- Enums, booleans, integer numbers, decimal numbers, `BigDecimal`, and
  `LocalDate` map to standard JSON Schema scalar types. `LocalDate` uses
  `type: string` and `format: date`.
- Jackson annotations such as `@JsonProperty`, `@JsonIgnore`, and date formatting
  are honoured where they affect the serialized JSON contract.
- `Optional<T>` means the field may be absent; the wrapped value type still
  defines the field schema when present.
- Raw `Object`, unbounded wildcard types, and other catch-all shapes should fail
  fast with a diagnostic error instead of producing a schema that cannot be
  audited.

Provider-facing schema can be weaker than the caller schema, but local
validation must not be. Current policy:

- OpenAI-compatible providers and Anthropic receive caller schemas unchanged where possible.
- Gemini receives a projected schema with local `$ref` inlined and nullable
  unions converted to `nullable: true`.
- DeepSeek uses JSON object mode; the full caller schema is enforced locally.

### 4. Validation and repair

Validation must be first-class because schema conformance is not enough for
business correctness.

Required validation categories:

- Type and required-field validation from schema.
- Enum and format validation.
- Field constraints such as `totalValue > 0`.
- Object constraints such as `partyA != partyB`.
- Citation constraints such as "these fields must cite source text".
- Confidence thresholds such as "low score warns" or "low score fails".

Repair policy should be explicit:

| Policy | Meaning |
| --- | --- |
| `FAIL_FAST` | One invalid response fails immediately. |
| `RETRY` | Re-ask the provider with validation errors. |
| `REPAIR` | Ask the provider to repair only the invalid JSON. |
| `WARN_AND_RETURN` | Return value with warnings for caller-owned review. |

The current `withFieldConstraint(...)` and `withObjectConstraint(...)` API is
the first slice of this layer.

### 5. Evidence-gated output

The output contract is not "the model returned JSON"; it is "the model returned
JSON whose important fields can be audited."

The result should expose:

- Typed value.
- Field-path to citation map.
- Field-path to confidence map.
- Validation warnings and repair attempts.
- Provenance: model, model version, extracted time, source published time,
  provider region, retry count.
- Audit export suitable for downstream systems.

## Target public API shape

The API should stay fluent and Java-native:

```java
record Contract(String partyA, String partyB, BigDecimal totalValue) {}

var result = DocTruth.from(provider)
        .extract("Extract contract terms", Contract.class)
        .withFieldConstraint(
                "totalValue",
                BigDecimal.class,
                value -> value.signum() > 0,
                "must be positive")
        .withObjectConstraint(
                contract -> !contract.partyA().equals(contract.partyB()),
                "partyA and partyB must differ")
        .withProvenance()
        .withConfidence()
        .withMaxRetries(2)
        .run(doc);
```

JSON Schema entry points preserve the same semantics while avoiding Java
overload ambiguity with `extract("prompt", null)`:

```java
var result = DocTruth.from(provider)
        .extractJson("Extract contract terms", JsonSchema.from(schemaPath))
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withMaxRetries(2)
        .runJson(doc);
```

The schema source changes; validation, repair, evidence gating, provenance, and
audit export stay the same.

## Pydantic compatibility

Pydantic compatibility should mean **JSON Schema compatibility**, not a Java
port of Pydantic.

Correct integration:

1. Python team exports a Pydantic model's JSON Schema.
2. Java service loads that JSON Schema.
3. DocTruth uses the schema for provider structured output and local validation.
4. DocTruth returns auditable JSON plus citations and provenance.

This lets Python-first teams reuse schema contracts without putting Python in
the Java runtime.

The compatibility layer validates the schema constructs Pydantic v2 commonly
emits for real nested models:

- Local `$defs` / `$ref` resolution.
- Nullable unions via `anyOf` and `type: ["T", "null"]`.
- `oneOf` / `allOf` composition.
- Required fields, nested properties, enum, array items, and
  `additionalProperties=false`.
- Common constraints: `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`,
  `exclusiveMinimum`, `exclusiveMaximum`, `minItems`, and `maxItems`.

This is still JSON Schema interoperability, not a Java clone of Pydantic's
runtime validators, serializers, computed fields, or Python plugin ecosystem.

A future migration CLI can make the build-time export path easier, for example:

```bash
doctruth migrate pydantic myapp.schemas:Resume --out resume.schema.json --check
```

That tool should remain outside the runtime core: it may invoke Python during
migration, but production Java services must only need the exported JSON Schema
file and the DocTruth jar.

The golden-path example is `examples/pydantic-interop`: it contains a nested
Pydantic-style resume schema, a Java `extractJson(...)` example with required
citations, and audit JSON output instructions.

External smoke coverage is split in two:

- `ExternalPdfCorpusSmokeIT` reads a caller-supplied local PDF corpus read-only
  and drives parse -> JSON extraction -> citation matching with a canned
  provider.
- `ExternalLlmSmokeIT` is live-only (`-Ddoctruth.live=true` / `-P live`) and
  reads provider keys from the process environment or a caller-supplied env file
  without printing key values. It reports provider success/failure categories,
  so stale local keys are visible without blocking recorded CI.

## Wrapper sequencing

The implementation order should be:

1. **Constraint layer**: field/object constraints, error codes, retry semantics,
   and tests.
2. **Schema validation layer**: JSON Schema input and local validation.
3. **Citation requirement layer**: per-field citation policies.
4. **Repair prompts**: validation-error-aware retry/repair messages.
5. **Provider wrappers**: provider-specific structured output modes.

Provider wrappers come after the constraint layer because the wrapper is not
the product. The product is reliable, evidence-gated structured extraction.

## Project boundary

The engine should be fully useful as a local Java library. If the library cannot
perform a complete auditable structured extraction locally, it will not earn
developer trust.

### In the generic library

These belong in DocTruth itself:

| Area | Reason |
| --- | --- |
| Common document parsers | Baseline adoption; cheap to replicate if withheld. |
| Citation matching and confidence | Core project identity. |
| Java record/class schema extraction | Core Java developer experience. |
| JSON Schema input | Core interoperability primitive. |
| Imported Pydantic JSON Schema | Compatibility layer, not domain IP. |
| Type/required/enum validation | Baseline structured extraction contract. |
| Field and object constraints | Core reliability primitive. |
| Retry and repair engine | Needed for a credible Instructor-class replacement. |
| Citation requirement policies | Core evidence-gated extraction. |
| Provider wrappers | Needed for a usable framework-agnostic engine. |
| Audit JSON export | Core auditability primitive. |

### Outside the generic library

These belong outside the generic extraction loop because they are deployment,
integration, organization-specific policy, or domain-maintained application
work:

| Area | Reason |
| --- | --- |
| Domain schema packs | Domain-maintained material, not generic extraction. |
| Jurisdiction-specific interpretation | Needs current domain ownership and review. |
| Observability connectors | Organization-specific integration. |
| Region/data-residency enforcement | Customer-specific infrastructure policy. |
| Managed key pools and vendor-key rotation | Operational integration outside the single-jar library. |
| Compliance dashboard and auditor portal | Application surface for compliance teams, not a Java primitive. |
| OCR engines and form-recognition models | Heavy runtime/model choices should be pluggable rather than bundled. |

Rule of thumb:

> If a competent engineer can copy it in a week, keep it in the core. If it requires
> continuous domain maintenance, organization-specific integration, or
> deployment operations, keep it outside the generic engine.

## Product bar

DocTruth should be able to claim the following without caveats:

1. A Java service can parse a document and extract a typed object.
2. The typed object is validated by schema and caller-defined constraints.
3. Important fields can be required to carry citations.
4. Failures are explicit, retryable where configured, and identified by stable
   error codes.
5. The caller receives value, citations, confidence, provenance, and audit JSON.

Anything short of that is a parser/evidence helper, not an auditable structured
extraction engine.
