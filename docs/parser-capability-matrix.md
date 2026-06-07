# Parser Capability Matrix

DocTruth parsing exists to preserve evidence anchors for extraction. It is not a
general document conversion product.

| Source | Text Anchor | Visual Anchor | Current Notes |
| --- | --- | --- | --- |
| PDF text | page, line, char offset | optional page-normalized bbox | Best-supported path for reviewer highlights |
| PDF scanned image | OCR adapter via `OcrEngine` SPI | OCR bbox when regions are supplied | Routed before DocTruth block assembly; no built-in OCR engine today |
| DOCX | paragraph-style logical sections | none | Word pagination is not stable without a renderer |
| XLSX | sheet/row-style logical sections | none | Cell-level bbox is future work |
| CSV | row/column-style logical sections | none | Logical tabular evidence only |
| PDF tables | section-level source location | future table/cell bbox | Table geometry is not yet a public contract |

Rules:

- `SourceLocation` is the durable audit anchor.
- `BoundingBox` is an optional visual anchor for PDF-originated text.
- Absence of bbox does not mean absence of evidence.
- Scanned PDFs should be routed through `OcrEngine` before DocTruth block assembly.
