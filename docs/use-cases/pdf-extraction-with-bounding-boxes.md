# PDF Extraction With Bounding Boxes

DocTruth can attach optional PDF bounding boxes to text sections and field
citations. This lets Java applications build source highlights without turning
DocTruth into a PDF viewer or layout engine.

## Coordinate System

`BoundingBox` uses page-normalized coordinates:

```java
public record BoundingBox(double x0, double y0, double x1, double y1) {}
```

Rules:

- origin is top-left
- coordinates are normalized to `0..1000`
- `x1 > x0`
- `y1 > y0`
- values are independent of page size and render DPI

Example:

```java
new BoundingBox(72.4, 118.0, 380.7, 142.5)
```

This means the evidence region starts about 7.2% from the left edge and 11.8%
from the top edge of the page.

## Parser To Citation Flow

```text
PDF text positions
→ TextSection with SourceLocation and optional BoundingBox
→ LLM extraction
→ Citation with exact quote and optional BoundingBox
→ audit JSON
```

The source location remains the durable text anchor. The bounding box is the
visual anchor.

## Java Example

```java
var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract contract terms", Contract.class)
        .withEvidence()
        .run();

var citation = result.requireCitation("partyA");

citation.boundingBox().ifPresent(bbox -> {
    System.out.println(bbox.x0());
    System.out.println(bbox.y0());
    System.out.println(bbox.x1());
    System.out.println(bbox.y1());
});
```

## When Bbox Is Absent

Bounding boxes are optional. A citation may not have one when:

- the source format is not visually paginated
- the parser could not recover reliable geometry
- the evidence came from a table or non-text source where cell-level geometry is
  not yet available

Applications should treat `boundingBox()` as an enhancement, not the only
evidence anchor. `SourceLocation` and `exactQuote` remain available for audit
flows.
