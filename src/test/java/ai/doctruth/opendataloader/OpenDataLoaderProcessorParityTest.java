package ai.doctruth.opendataloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OpenDataLoaderProcessorParityTest {

    private static final Path REPORT = Path.of("docs/parser/opendataloader-processor-gap-report.md");
    private static final Set<String> ALLOWED_STATUS = Set.of("matched", "partial", "oracle-only", "missing");
    private static final Set<String> REQUIRED_AREAS = Set.of(
            "PDF text normalization",
            "Hidden/off-page/tiny/background text filtering",
            "Duplicate text suppression",
            "XY-Cut geometry reading order",
            "Paragraph and line merging",
            "List grouping",
            "Heading promotion and hierarchy",
            "Header/footer furniture",
            "Table detection",
            "Borderless table clustering",
            "Table cell grid reconstruction",
            "Caption binding",
            "OCR region routing",
            "Scanned PDF error semantics");

    @Test
    void processorGapReportTracksEveryOpenDataLoaderParityArea() throws IOException {
        var rows = processorRows();

        assertThat(rows).hasSize(REQUIRED_AREAS.size());
        assertThat(rows.stream().map(Row::area).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(REQUIRED_AREAS);
    }

    @Test
    void processorStatusesAreConservativeAndEvidenceBound() throws IOException {
        for (var row : processorRows()) {
            assertThat(ALLOWED_STATUS).as(row.area()).contains(row.status());
            if ("matched".equals(row.status())) {
                assertThat(row.focusedTest()).as(row.area()).isNotEqualTo("TBD");
                assertThat(row.fullBenchEvidence()).as(row.area()).isNotEqualTo("TBD");
            }
        }
    }

    @Test
    void latestLowScoreBucketsHaveProcessorOwnersAndNextActions() throws IOException {
        var matrix = Files.readString(Path.of("docs/parser/opendataloader-parity-matrix.md"));

        assertThat(matrix).contains("| heading_hierarchy | HeadingProcessor |");
        assertThat(matrix).contains("| two_column_reading_order | TaggedDocumentProcessor |");
        assertThat(matrix).contains("| sidebar_reading_order | TaggedDocumentProcessor |");
        assertThat(matrix).contains("| text_noise_filtering | ContentFilterProcessor |");
        assertThat(matrix).contains("| bordered_tables | TableBorderProcessor |");
        assertThat(matrix).contains("| borderless_tables | ClusterTableProcessor |");

        assertThat(matrix).contains("Next Processor Work");
        assertThat(matrix)
                .contains(
                        "| Processor | Metric bucket | Behavior buckets | Current cases | Current metric | Next action |");
        assertThat(matrix)
                .contains(
                        "| HeadingProcessor | heading_hierarchy | heading_hierarchy | 39 | mhs | continue generalized heading hierarchy reconstruction for remaining non-numbered and complex section tree misses |");
        assertThat(matrix)
                .contains(
                        "| TaggedDocumentProcessor | reading_order | two_column_reading_order; sidebar_reading_order | 15 | nid | port generalized tagged reading-order reconstruction for two-column and sidebar layouts |");
        assertThat(matrix)
                .contains(
                        "| TableStructureNormalizer | table_structure | bordered_tables; borderless_tables | 5 | teds | port generalized table structure normalization before adding more table case repairs |");
        assertThat(matrix)
                .contains(
                        "| SpecialTableProcessor | overall_quality | table_false_positive_rejection; text_noise_filtering | 9 | overall/teds | port generalized false-table and text-noise overlap rejection gates |");
        assertThat(matrix)
                .contains(
                        "| ContentFilterProcessor | overall_quality | text_noise_filtering | 9 | overall | port generalized text-noise filtering for latest full200 noisy-content failures |");
        assertThat(matrix).doesNotContain("two_column_reading_order,sidebar_reading_order");
        assertThat(matrix).doesNotContain("table_false_positive_rejection,text_noise overlap");
    }

    private static List<Row> processorRows() throws IOException {
        assertThat(REPORT).isRegularFile();
        return Files.readAllLines(REPORT).stream()
                .filter(line -> line.startsWith("| "))
                .filter(line -> !line.contains("---"))
                .skip(1)
                .map(OpenDataLoaderProcessorParityTest::parseRow)
                .toList();
    }

    private static Row parseRow(String line) {
        var cells = line.substring(1, line.length() - 1).split("\\|");
        assertThat(cells).hasSize(5);
        return new Row(cells[0].trim(), cells[1].trim(), unquote(cells[2].trim()), cells[3].trim(), cells[4].trim());
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record Row(String area, String status, String focusedTest, String fullBenchEvidence, String notes) {}
}
