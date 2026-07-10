package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke test: walk {@code fixtures/xlsx/} and assert ≥95% parse cleanly. Skipped
 * automatically when the fixtures directory is absent or empty so contributors without local
 * fixtures still get a green build.
 *
 * <p>Runs under {@code mvn verify} (gated by the {@code IT} suffix); not part of {@code mvn test}.
 */
@DisplayName("real-world XLSX fixture smoke test")
class RealWorldXlsxFixtureIT {

    private static final Path FIXTURE_DIR = Path.of("fixtures/xlsx");

    @BeforeAll
    static void skipIfFixturesMissing() throws IOException {
        boolean hasFixtures = Files.isDirectory(FIXTURE_DIR) && hasAnyXlsx(FIXTURE_DIR);
        assumeTrue(hasFixtures, "fixtures/xlsx empty or missing — skipping real-world IT");
    }

    @Test
    @DisplayName("≥95% of fixtures/xlsx/*.xlsx parse without exception")
    void fixturesParseCleanly() throws IOException {
        List<Path> xlsxFiles;
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            xlsxFiles = stream.filter(
                            p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .toList();
        }
        assertThat(xlsxFiles).isNotEmpty();

        var failures = new ArrayList<String>();
        int succeeded = 0;
        for (Path file : xlsxFiles) {
            try {
                var doc = XlsxDocumentParser.parse(file);
                assertThat(doc.docId()).startsWith("sha256:");
                assertThat(doc.metadata().pageCount()).isGreaterThanOrEqualTo(1);
                assertThat(doc.sections()).isNotEmpty();
                succeeded++;
            } catch (Exception | AssertionError e) {
                failures.add(file.getFileName() + ": " + e.getMessage());
            }
        }

        double passRate = (double) succeeded / xlsxFiles.size();
        assertThat(passRate)
                .as("real-world XLSX pass rate (%d/%d, failures: %s)", succeeded, xlsxFiles.size(), failures)
                .isGreaterThanOrEqualTo(0.95);
    }

    private static boolean hasAnyXlsx(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"));
        }
    }
}
