package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

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
