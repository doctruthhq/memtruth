# Evidence Schema

DocTruth's core output is not just a typed value. It is a typed value plus the
evidence needed to defend every extracted field.

The evidence model has four layers:

```text
ParsedDocument
→ SourceLocation / BoundingBox
→ Citation
→ ExtractionResult / audit JSON
```

## Parsed Document

`ParsedDocument` is the normalized document shape used by extraction and
citation matching.

```java
public record ParsedDocument(
        String docId,
        List<ParsedSection> sections,
        DocumentMetadata metadata) {}
```

It is intentionally small. It does not try to be a PDF object model, a DOM, or a
layout engine. It gives DocTruth enough stable source text to assemble context
and enough location data to prove where an extracted field came from.

## Parsed Sections

`ParsedSection` is a sealed family:

```java
public sealed interface ParsedSection permits TextSection, TableSection, FigureSection {}
```

Current section types:

| Type | Purpose |
| --- | --- |
| `TextSection` | Text block with source location, block kind, and optional PDF bbox |
| `TableSection` | Logical table rows with source location |
| `FigureSection` | Figure caption with source location |

## Source Location

`SourceLocation` is the text anchor:

```java
public record SourceLocation(
        int pageStart,
        int pageEnd,
        int lineStart,
        int lineEnd,
        int charOffset) {}
```

Semantics:

| Field | Meaning |
| --- | --- |
| `pageStart` / `pageEnd` | 1-indexed source page range |
| `lineStart` / `lineEnd` | 1-indexed logical line range inside the parsed document |
| `charOffset` | Character offset inside the rendered page text |

For non-paginated formats such as CSV, DocTruth maps logical sheets/rows into
page and line anchors so the downstream contract stays consistent.

## Bounding Box

`BoundingBox` is the visual anchor:

```java
public record BoundingBox(double x0, double y0, double x1, double y1) {}
```

Semantics:

| Field | Meaning |
| --- | --- |
| `x0` | Left edge |
| `y0` | Top edge |
| `x1` | Right edge |
| `y1` | Bottom edge |

Rules:

- Coordinates are page-normalized to `0..1000`.
- Origin is top-left.
- `x1 > x0` and `y1 > y0`.
- Values are independent of PDF page size and render DPI.
- Bounding boxes are optional because not every source format has reliable page geometry.

Example:

```java
var bbox = new BoundingBox(72.4, 118.0, 380.7, 142.5);
```

This means the evidence region starts about 7.2% from the left edge and 11.8%
from the top edge of the page.

## Text Section With Bbox

PDF-originated text sections can carry a bounding box:

```java
public record TextSection(
        String text,
        SourceLocation location,
        BlockKind kind,
        Optional<BoundingBox> boundingBox) implements ParsedSection {}
```

`BlockKind` is a coarse layout hint:

```java
public enum BlockKind {
    HEADING,
    BODY,
    LIST,
    OTHER
}
```

Use `TextSection.boundingBox()` when building source overlays or reviewer
highlight UIs. Use `TextSection.location()` when storing durable text anchors.

## Citation

`Citation` is the field-level evidence anchor:

```java
public record Citation(
        SourceLocation location,
        String exactQuote,
        double matchScore,
        Optional<BoundingBox> boundingBox) {}
```

Semantics:

| Field | Meaning |
| --- | --- |
| `location` | Source text location for the matched field |
| `exactQuote` | Source quote used as evidence |
| `matchScore` | Similarity score in `[0.0, 1.0]` |
| `boundingBox` | Optional visual region for PDF-originated evidence |

`matchScore == 1.0` means exact substring match. Lower scores come from fuzzy
matching and should be treated as warnings by downstream systems. The default
strong-citation threshold is `0.85`.

## Extraction Result

`ExtractionResult<T>` is the main return object:

```java
public record ExtractionResult<T>(
        T value,
        Map<String, Citation> citations,
        Map<String, Confidence> confidence,
        Provenance provenance) {}
```

Field paths use Java/JSON-style names:

```text
partyA
totalValue
lineItems[0].amount
members[1].address.city
```

Example:

```java
var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract contract terms", Contract.class)
        .withEvidence()
        .run();

var citation = result.requireCitation("totalValue");

System.out.println(citation.exactQuote());
System.out.println(citation.location().pageStart());
citation.boundingBox().ifPresent(System.out::println);
```

## Audit JSON

`ExtractionResult.toAuditJson()` exports a W3C PROV-O compatible JSON-LD
document with DocTruth-specific evidence fields.

Compact shape:

```json
{
  "@context": "https://www.w3.org/ns/prov",
  "@type": "prov:Entity",
  "doctruth:value": {
    "partyA": "Acme Industrial Materials Pty Ltd",
    "totalValue": 2450000
  },
  "doctruth:retries": 0,
  "prov:wasGeneratedBy": {
    "@type": "prov:Activity",
    "prov:startedAtTime": "2026-05-07T05:30:14.218Z",
    "prov:wasAssociatedWith": {
      "@type": "prov:SoftwareAgent",
      "rdfs:label": "openai",
      "prov:version": "gpt-4o"
    }
  },
  "prov:wasDerivedFrom": [
    {
      "@type": "prov:Entity",
      "doctruth:fieldPath": "partyA",
      "prov:value": "Acme Industrial Materials Pty Ltd",
      "doctruth:matchScore": 1.0,
      "doctruth:sourceLocation": {
        "pageStart": 1,
        "pageEnd": 1,
        "lineStart": 2,
        "lineEnd": 2,
        "charOffset": 31
      },
      "doctruth:boundingBox": {
        "x0": 72.4,
        "y0": 118.0,
        "x1": 380.7,
        "y1": 142.5
      }
    }
  ],
  "doctruth:confidence": {
    "partyA": {
      "score": 1.0,
      "rationale": "exact substring match"
    }
  }
}
```

`doctruth:boundingBox` is emitted only when a citation has visual geometry.

## Design Rules

DocTruth evidence should stay:

- **Source-grounded**: every citation points at source text, not model rationale.
- **Portable**: audit JSON can be stored or handed to another system.
- **Optional where honest**: bbox is absent when source geometry is unavailable.
- **Warning-friendly**: weak citation matches are surfaced, not silently dropped.
- **Java-native**: records and maps expose the contract without framework types.

## Common Edge Cases

| Case | Expected Behavior |
| --- | --- |
| Exact quote found | `matchScore == 1.0`, citation carries the section location and bbox if present |
| Fuzzy quote found | Citation is returned with lower `matchScore`; callers can warn, retry, or review |
| No source text match | Citation is returned with `matchScore == 0.0` so the failure is explicit |
| Source has no bbox | `boundingBox()` is `Optional.empty()` |
| Multi-page field | Current citation points to the best matching section; richer span sets are future work |
| Table values | Citation uses table source location; cell-level bbox is future work |
