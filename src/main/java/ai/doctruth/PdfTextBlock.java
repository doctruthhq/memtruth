package ai.doctruth;

import java.util.Optional;

/** Internal carrier for one layout block detected on a PDF page. */
record PdfTextBlock(String text, BlockKind kind, SourceLocation location, Optional<BoundingBox> boundingBox) {}
