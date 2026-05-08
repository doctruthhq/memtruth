package ai.doctruth;

/** Internal carrier for one layout block detected on a PDF page. */
record PdfTextBlock(String text, BlockKind kind, SourceLocation location) {}
