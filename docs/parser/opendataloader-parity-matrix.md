# OpenDataLoader Parity Matrix

This matrix tracks DocTruth runtime parity against the Apache-2.0
OpenDataLoader PDF processor surface. Status values are conservative and do not
claim parser behavior that has not been ported or verified in DocTruth.

## Status Values

- `ported`: behavior is implemented and covered in DocTruth runtime tests.
- `partial`: related behavior exists, but parity is incomplete or still under
  verification.
- `not_ported`: no DocTruth-owned runtime equivalent has been added yet.
- `oracle_only`: used as an external comparison or schema reference, not as a
  DocTruth runtime implementation.
- `intentionally_skipped`: out of scope for DocTruth runtime by design.

## DocumentProcessor

Status: `partial`. DocTruth has document-level parsing and `TrustDocument`
emission, but full OpenDataLoader processor parity is not yet claimed.

## TaggedDocumentProcessor

Status: `partial`. Tagged or structured PDF signals are part of the runtime
direction, but complete upstream behavior remains under parity review.

## TextProcessor

Status: `partial`. Native text extraction exists through the Rust PDF substrate,
but upstream text processing parity is still incomplete.

## TextLineProcessor

Status: `partial`. Text line handling exists in the runtime, but line grouping
has not been certified against the upstream processor.

## ParagraphProcessor

Status: `partial`. Paragraph-like grouping is present only as partial structure
recovery and requires further OpenDataLoader parity coverage.

## HeadingProcessor

Status: `partial`. Heading signals exist in parser-quality work, but upstream
heading processor parity is still under verification.

## ListProcessor

Status: `partial`. List detection is treated as partial document structure
recovery and is not yet a full upstream processor port.

## CaptionProcessor

Status: `not_ported`. A dedicated caption processor equivalent has not been
ported into the DocTruth Rust runtime.

## LevelProcessor

Status: `partial`. Structural level handling exists only as partial layout and
reading-order recovery.

## HeaderFooterProcessor

Status: `partial`. Header and footer treatment is not yet a complete upstream
processor port.

## ContentFilterProcessor

Status: `partial`. Content filtering behavior exists in parser-quality work, but
the upstream processor is not fully ported.

## TextDecorationProcessor

Status: `partial`. Decoration signals such as underline and strike handling are
covered in part, but full upstream parity is not claimed.

## TableBorderProcessor

Status: `partial`. Table border signals are handled in part through Rust table
recognition, with upstream parity still incomplete.

## ClusterTableProcessor

Status: `partial`. Cluster-table behavior is represented in current parser
direction, but the upstream processor is not fully ported.

## SpecialTableProcessor

Status: `partial`. Special table cases are tracked as partial table-recognition
coverage until parity tests prove the behavior.

## TableStructureNormalizer

Status: `partial`. Table normalization exists only in partial form and remains a
known parity area.

## HiddenTextProcessor

Status: `not_ported`. The upstream hidden-text detector is tracked as part of
the OpenDataLoader surface, but a dedicated DocTruth Rust runtime equivalent has
not been ported or verified.

## HybridDocumentProcessor

Status: `partial`. Hybrid parsing is represented by runtime orchestration and
model slots, but upstream hybrid behavior is not fully ported.

## TriageProcessor

Status: `partial`. Runtime routing and warnings cover some triage concerns, but
the upstream processor is not fully ported.

## DoclingSchemaTransformer

Status: `oracle_only`. Docling-style schema transformation is treated as a
comparison or oracle surface, not as a DocTruth runtime output contract.

## OcrStrategy

Status: `partial`. OCR routing is part of the runtime contract, but full
OpenDataLoader strategy parity has not been verified.
