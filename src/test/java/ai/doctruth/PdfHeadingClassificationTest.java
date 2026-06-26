package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PdfHeadingClassificationTest {

    @Test
    @DisplayName("title-case known resume section names at body size are headings")
    void titleCaseKnownSectionNamesAtBodySizeAreHeadings() {
        assertThat(PdfDocumentParser.classify("Work Experience", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
        assertThat(PdfDocumentParser.classify("Professional Experience", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
        assertThat(PdfDocumentParser.classify("Latar Belakang Pendidikan", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
    }

    @Test
    @DisplayName("standalone title-case document section names at body size are headings")
    void standaloneTitleCaseDocumentSectionNamesAtBodySizeAreHeadings() {
        assertThat(PdfDocumentParser.classify("Narratives in Chuj", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
        assertThat(PdfDocumentParser.classify("Introduction to the Texts", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
        assertThat(PdfDocumentParser.classify("7 Variants of SJ Observer Models", 12.0, 12.0))
                .isEqualTo(BlockKind.HEADING);
    }

    @Test
    @DisplayName("known section words embedded in field values stay body")
    void knownSectionWordsEmbeddedInFieldValuesStayBody() {
        assertThat(PdfDocumentParser.classify("Experience: Five years in logistics", 12.0, 12.0))
                .isEqualTo(BlockKind.BODY);
        assertThat(PdfDocumentParser.classify("Quality: Checked incoming stock", 12.0, 12.0))
                .isEqualTo(BlockKind.BODY);
    }

    @Test
    @DisplayName("known section words embedded in normal sentences stay body")
    void knownSectionWordsEmbeddedInNormalSentencesStayBody() {
        assertThat(PdfDocumentParser.classify(
                        "The work experience includes logistics and customer support.", 12.0, 12.0))
                .isEqualTo(BlockKind.BODY);
    }

    @Test
    @DisplayName("page labels and sentence-like title-case text stay body")
    void pageLabelsAndSentenceLikeTitleCaseTextStayBody() {
        assertThat(PdfDocumentParser.classify("Chapter 2", 12.0, 12.0))
                .isEqualTo(BlockKind.BODY);
        assertThat(PdfDocumentParser.classify(
                        "This Collection of Six Narratives Told in Chuj Demonstrates the Broad Variety.",
                        12.0,
                        12.0))
                .isEqualTo(BlockKind.BODY);
    }
}
