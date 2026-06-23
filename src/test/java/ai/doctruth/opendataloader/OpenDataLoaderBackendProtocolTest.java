package ai.doctruth.opendataloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.BoundingBox;
import ai.doctruth.ParserPreset;
import ai.doctruth.ParserWarning;
import ai.doctruth.ParserWarningSeverity;
import ai.doctruth.TrustDocument;
import org.junit.jupiter.api.Test;

class OpenDataLoaderBackendProtocolTest {

    @Test
    void requestRejectsMissingDocumentAndPreset() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OpenDataLoaderBackendRequest(null, ParserPreset.LITE))
                .withMessageContaining("document");
        assertThatNullPointerException()
                .isThrownBy(() -> new OpenDataLoaderBackendRequest(Path.of("x.pdf"), null))
                .withMessageContaining("preset");
    }

    @Test
    void responseDefensivelyCopiesMutableCollections() {
        var blocks = new ArrayList<OpenDataLoaderBlock>();
        var block = new OpenDataLoaderBlock(
                "block-1",
                "text",
                0,
                Optional.of(new BoundingBox(1, 2, 3, 4)),
                1,
                "hello",
                "unit-1");
        blocks.add(block);
        var warnings = new ArrayList<ParserWarning>();
        warnings.add(new ParserWarning("x", ParserWarningSeverity.INFO, "x"));

        var response = OpenDataLoaderBackendResponse.fromParts(
                "opendataloader-java-core",
                "doctruth.opendataloader.backend.v1",
                "# hello\n",
                blocks,
                List.of(),
                List.of(),
                List.of(new OpenDataLoaderSourceRef("unit-1", 0, Optional.empty(), "hello")),
                warnings,
                Map.of("elapsedMs", 1L),
                minimalTrustDocument());
        blocks.clear();
        warnings.clear();

        assertThat(response.blocks()).hasSize(1);
        assertThat(response.warnings()).hasSize(1);
        assertThatThrownBy(() -> response.blocks().add(block))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TrustDocument minimalTrustDocument() {
        var parsed = new ai.doctruth.ParsedDocument(
                "doc-1",
                List.of(new ai.doctruth.TextSection(
                        "hello",
                        new ai.doctruth.SourceLocation(1, 1, 1, 1, 0),
                        ai.doctruth.BlockKind.BODY,
                        Optional.empty())),
                new ai.doctruth.DocumentMetadata("x.pdf", 1, Optional.empty()));
        return TrustDocument.fromParsed(
                parsed,
                "sha256:source",
                new ai.doctruth.ParserRun("1.0.0", "lite", "opendataloader-java-core", List.of(), List.of()));
    }
}
