package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the PRD-style SDK parser entrypoint. */
class TrustDocumentSdkParserContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("document-first SDK can parse a TrustDocument with an explicit parser preset")
    void sdkParserPresetProducesTrustDocument() throws Exception {
        var pdf = writePdf("TrustDocument SDK parser path.");

        var doc = DocTruth.withProvider(provider())
                .fromPdf(pdf)
                .withParser(ParserPreset.LITE)
                .parse();

        assertThat(doc).isInstanceOf(TrustDocument.class);
        assertThat(doc.parserRun().preset()).isEqualTo("lite");
        assertThat(doc.parserRun().backend()).isEqualTo("pdfbox");
        assertThat(doc.toMarkdownClean()).contains("TrustDocument SDK parser path.");
    }

    @Test
    @DisplayName("standard preset records model fallback when cache is unavailable offline")
    void standardPresetRecordsOfflineModelFallback() throws Exception {
        var pdf = writePdf("Standard parser should expose model fallback.");

        var doc = DocTruth.withProvider(provider())
                .fromPdf(pdf)
                .withParser(ParserPreset.STANDARD)
                .parse();

        assertThat(doc.parserRun().preset()).isEqualTo("standard");
        assertThat(doc.parserRun().models()).contains("layout-rtdetr:v2", "tatr:v1");
        assertThat(doc.parserRun().warnings()).extracting(ParserWarning::code).contains("model_unavailable_fallback");
        assertThat(doc.auditGradeStatus()).isEqualTo(AuditGradeStatus.NOT_AUDIT_GRADE);
    }

    @Test
    @DisplayName("path-first SDK parser uses configured Rust runtime in auto backend mode")
    void pathFirstSdkParserUsesConfiguredRustRuntimeInAutoMode() throws Exception {
        var pdf = writePdf("PDFBox SDK parser text should not win.");
        var runtime = fakeRustRuntime("Rust SDK parser text.");

        withSystemProperty("doctruth.runtime.command", runtime.toString(), () -> {
            var doc = DocTruth.withProvider(provider())
                    .parsePdf(pdf)
                    .withParser(ParserPreset.LITE)
                    .backend(ParserBackendMode.AUTO)
                    .parse();

            assertThat(doc.parserRun().backend()).isEqualTo("sidecar");
            assertThat(doc.toMarkdownClean())
                    .contains("Rust SDK parser text.")
                    .doesNotContain("PDFBox SDK parser text should not win.");
        });
    }

    @Test
    @DisplayName("path-first SDK parser can force Java PDFBox fallback")
    void pathFirstSdkParserCanForcePdfBoxFallback() throws Exception {
        var pdf = writePdf("Explicit SDK PDFBox fallback.");
        var runtime = fakeRustRuntime("Rust should not win explicit fallback.");

        withSystemProperty("doctruth.runtime.command", runtime.toString(), () -> {
            var doc = DocTruth.withProvider(provider())
                    .parsePdf(pdf)
                    .withParser(ParserPreset.LITE)
                    .backend(ParserBackendMode.PDFBOX)
                    .parse();

            assertThat(doc.parserRun().backend()).isEqualTo("pdfbox");
            assertThat(doc.toMarkdownClean())
                    .contains("Explicit SDK PDFBox fallback.")
                    .doesNotContain("Rust should not win explicit fallback.");
        });
    }

    @Test
    @DisplayName("path-first SDK Rust-default modes require a configured runtime")
    void pathFirstSdkRustDefaultModesRequireRuntime() throws Exception {
        var pdf = writePdf("Missing sidecar runtime.");

        withSystemProperty("doctruth.runtime.disableSourceDiscovery", "true", () -> {
            assertThatThrownBy(() -> DocTruth.withProvider(provider())
                            .parsePdf(pdf)
                            .withParser(ParserPreset.LITE)
                            .backend(ParserBackendMode.AUTO)
                            .parse())
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Rust runtime is required");

            assertThatThrownBy(() -> DocTruth.withProvider(provider())
                            .parsePdf(pdf)
                            .withParser(ParserPreset.LITE)
                            .backend(ParserBackendMode.SIDECAR)
                            .parse())
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Rust runtime is required");
        });
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve("sdk.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private LlmProvider provider() {
        return new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest request) {
                throw new UnsupportedOperationException("parser tests must not call an LLM provider");
            }
        };
    }

    private Path fakeRustRuntime(String text) throws IOException {
        Path runtime = tempDir.resolve("fake-doctruth-runtime");
        Files.writeString(runtime, """
                #!/usr/bin/env sh
                cat >/dev/null
                cat <<'JSON'
                {"docId":"sha256:rust-sdk","source":{"sourceFilename":"runtime.pdf","sourceHash":"sha256:rust-sdk","metadata":{"sourceFilename":"runtime.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"LINE_SPAN","page":1,"text":"%s","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1},"sourceObjectId":"runtime-line-1","confidence":{"score":1.0,"rationale":"rust runtime"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"lite","backend":"sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """.formatted(text), StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        var previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
