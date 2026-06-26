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
}
