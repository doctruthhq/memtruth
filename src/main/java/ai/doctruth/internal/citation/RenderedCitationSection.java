package ai.doctruth.internal.citation;

import java.util.Optional;

import ai.doctruth.BoundingBox;
import ai.doctruth.SourceLocation;

record RenderedCitationSection(
        String text,
        SourceLocation location,
        Optional<BoundingBox> boundingBox,
        String sourceDocId,
        String sourceUnitId) {}
