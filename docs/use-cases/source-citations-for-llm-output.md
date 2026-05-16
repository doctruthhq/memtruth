# Source Citations for LLM Output

LLM output is hard to operationalize when nobody can verify where a field came
from. DocTruth adds source citations to structured extraction results so a Java
application can show, store, review, and export the evidence behind each field.

## Citation Contract

A DocTruth citation contains:

```java
public record Citation(
        SourceLocation location,
        String exactQuote,
        double matchScore,
        Optional<BoundingBox> boundingBox) {}
```

This gives the caller both a text anchor and, when available, a visual PDF
anchor.

## Example

```java
var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("invoice.pdf"))
        .extract("Extract invoice fields", Invoice.class)
        .withEvidence()
        .run();

var citation = result.requireCitation("invoiceNumber");

System.out.println(citation.exactQuote());
System.out.println(citation.location());
System.out.println(citation.matchScore());
```

If the source is a PDF and geometry is available, the citation can also include
a page-normalized bounding box for highlight overlays:

```java
citation.boundingBox().ifPresent(System.out::println);
```

## Match Scores

`matchScore` is intentionally surfaced instead of hidden.

| Score | Meaning |
| --- | --- |
| `1.0` | Exact source quote match |
| `0.85..0.99` | Strong fuzzy match |
| Below strong threshold | Needs warning, retry, or review |
| `0.0` | No source text match found |

Weak evidence should be visible to the application. DocTruth does not silently
drop citation failures.

## Reviewer UI

DocTruth is not a UI framework, but the citation contract supports review
interfaces:

```text
field value
→ exact source quote
→ page and line
→ optional PDF bbox
→ reviewer approve / reject
```

For a minimal overlay example, see
[examples/evidence-overlay](../../examples/evidence-overlay/).
