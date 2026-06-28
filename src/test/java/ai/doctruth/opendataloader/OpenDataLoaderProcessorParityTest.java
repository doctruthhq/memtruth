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
