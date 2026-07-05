package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenDataLoaderPdfDocumentParserTest {

    @TempDir
    Path tempDir;

    @Test
    void opendataloaderBackendEmitsDocTruthSectionsWithPageAnchors() throws Exception {
        Path pdf = writeMultiPagePdf(tempDir, List.of("OpenDataLoader first page", "OpenDataLoader second page"));

        ParsedDocument doc = PdfDocumentParser.parse(pdf, PdfParserBackend.OPENDATALOADER);

        assertThat(doc.docId()).startsWith("sha256:");
        assertThat(doc.metadata().sourceFilename()).isEqualTo(pdf.getFileName().toString());
        assertThat(doc.metadata().pageCount()).isEqualTo(2);
        assertThat(doc.sections()).isNotEmpty();
        assertThat(doc.sections())
                .filteredOn(TextSection.class::isInstance)
                .map(TextSection.class::cast)
                .extracting(TextSection::text)
                .anySatisfy(text -> assertThat(text).contains("OpenDataLoader first page"))
                .anySatisfy(text -> assertThat(text).contains("OpenDataLoader second page"));
        assertThat(doc.sections())
                .filteredOn(TextSection.class::isInstance)
                .map(TextSection.class::cast)
                .allSatisfy(section -> {
                    assertThat(section.location().pageStart()).isBetween(1, 2);
                    assertThat(section.location().pageEnd())
                            .isEqualTo(section.location().pageStart());
                });
    }

    @Test
    void defaultPdfBackendIsOpenDataLoader() throws Exception {
        Path pdf = writeMultiPagePdf(tempDir, List.of("default backend text"));

        ParsedDocument defaultDoc = PdfDocumentParser.parse(pdf);
        ParsedDocument explicitDoc = PdfDocumentParser.parse(pdf, PdfParserBackend.OPENDATALOADER);

        assertThat(defaultDoc.sections()).isEqualTo(explicitDoc.sections());
    }

    @Test
    void streamsOpenDataLoaderJsonIntoParsedSections() throws Exception {
        Path json = tempDir.resolve("sample.json");
        Files.writeString(json, """
                {
                  "number of pages": 2,
                  "producer metadata": { "ignored": [1, 2, 3] },
                  "kids": [
                    {
                      "type": "heading",
                      "page number": 1,
                      "bounding box": [20, 100, 100, 150],
                      "content": "Streaming Heading"
                    },
                    {
                      "type": "table",
                      "page number": 2,
                      "rows": [
                        { "cells": [{ "content": "A1" }, { "content": "B1" }] }
                      ]
                    }
                  ]
                }
                """);
        var geometry = new OpenDataLoaderPdfGeometry(
                Map.of(1, new OpenDataLoaderPdfGeometry.PageGeometry(200.0, 400.0)));

        var parsed = OpenDataLoaderPdfDocumentParser.readOpenDataLoaderJson(json, geometry);

        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.sections()).hasSize(2);
        assertThat((TextSection) parsed.sections().get(0))
                .extracting(TextSection::text, TextSection::kind)
                .containsExactly("Streaming Heading", BlockKind.HEADING);
        assertThat((TableSection) parsed.sections().get(1))
                .extracting(TableSection::rows)
                .isEqualTo(List.of(List.of("A1", "B1")));
    }

    @Test
    void rejectsOpenDataLoaderJsonThatDoesNotStartWithObject() throws Exception {
        Path json = tempDir.resolve("array-root.json");
        Files.writeString(json, "[]");
        var geometry = new OpenDataLoaderPdfGeometry(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> OpenDataLoaderPdfDocumentParser.readOpenDataLoaderJson(json, geometry))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("OpenDataLoader JSON root must be an object");
    }

    @Test
    void rejectsOpenDataLoaderJsonWithNonObjectKids() throws Exception {
        Path json = tempDir.resolve("non-object-kid.json");
        Files.writeString(json, """
                {
                  "number of pages": 1,
                  "kids": [null]
                }
                """);
        var geometry = new OpenDataLoaderPdfGeometry(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> OpenDataLoaderPdfDocumentParser.readOpenDataLoaderJson(json, geometry))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("OpenDataLoader kids entries must be objects");
    }

    @Test
    void rejectsOpenDataLoaderJsonWithNonArrayKidsField() throws Exception {
        Path json = tempDir.resolve("non-array-kids.json");
        Files.writeString(json, """
                {
                  "number of pages": 1,
                  "kids": {}
                }
                """);
        var geometry = new OpenDataLoaderPdfGeometry(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> OpenDataLoaderPdfDocumentParser.readOpenDataLoaderJson(json, geometry))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("OpenDataLoader kids field must be an array");
    }

    @Test
    void serializesOpenDataLoaderProcessLifecycle() throws Exception {
        Path first = tempDir.resolve("first.pdf");
        Path second = tempDir.resolve("second.pdf");
        Files.writeString(first, "fake pdf bytes");
        Files.writeString(second, "fake pdf bytes");
        var activeRuns = new AtomicInteger();
        var maxActiveRuns = new AtomicInteger();
        var calls = new AtomicInteger();
        OpenDataLoaderPdfDocumentParser.OpenDataLoaderRunner runner = (pdfPath, outputDir, config) -> {
            calls.incrementAndGet();
            int active = activeRuns.incrementAndGet();
            maxActiveRuns.accumulateAndGet(active, Math::max);
            try {
                sleepBriefly();
                String outputName = pdfPath.getFileName().toString().replaceFirst("\\.[^.]+$", "") + ".json";
                Files.writeString(outputDir.resolve(outputName), """
                        {
                          "number of pages": 1,
                          "kids": [
                            { "type": "heading", "page number": 1, "content": "serialized" }
                          ]
                        }
                        """);
            } finally {
                activeRuns.decrementAndGet();
            }
        };

        try (var runnerOverride = OpenDataLoaderPdfDocumentParser.useOpenDataLoaderRunnerForTesting(runner)) {
            assertThat(runnerOverride).isNotNull();
            var executor = Executors.newFixedThreadPool(2);
            try {
                var firstResult = executor.submit(() -> OpenDataLoaderPdfDocumentParser.parse(first));
                var secondResult = executor.submit(() -> OpenDataLoaderPdfDocumentParser.parse(second));
                assertThat(firstResult.get(5, TimeUnit.SECONDS).sections()).hasSize(1);
                assertThat(secondResult.get(5, TimeUnit.SECONDS).sections()).hasSize(1);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(calls).hasValue(2);
        assertThat(maxActiveRuns).hasValue(1);
    }

    private static void sleepBriefly() throws java.io.IOException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("interrupted while simulating OpenDataLoader", e);
        }
    }

    private static Path writeMultiPagePdf(Path dir, List<String> pageTexts) throws Exception {
        Path path = dir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(path.toFile());
        }
        return path;
    }
}
