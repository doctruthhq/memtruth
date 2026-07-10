package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-world fixture smoke test for {@link CsvDocumentParser}.
 *
 * <p>Iterates every {@code .csv} file under {@code fixtures/csv/} and asserts the parser
 * survives the wild-CSV chaos at &gt;= 90% pass rate. Below that bar we treat it as a
 * regression in the parser's robustness budget for v0.1.0-alpha. CSV-format chaos in the
 * wild — Latin-1 / Windows-1252 / BOM-prefixed UTF-8 / non-comma delimiter / mixed quoting
 * — caps a realistic-but-honest target at 90% for v0.1.0-alpha.
 *
 * <p>Skipped cleanly when {@code fixtures/csv/} is absent or empty.
 */
class RealWorldCsvFixtureIT {

    private static final Logger LOG = LoggerFactory.getLogger(RealWorldCsvFixtureIT.class);

    private static final Path FIXTURES_DIR = Path.of("fixtures/csv");
    private static final double MIN_PASS_RATE = 0.90;

    @Test
    @DisplayName("real-world CSV fixtures parse at >= 90% pass rate")
    void realWorldCsvFixturesPassRate() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(FIXTURES_DIR), "fixtures/csv directory missing — skipping");

        Map<String, Integer> failureCounts = new TreeMap<>();
        var passes = new int[] {0};
        var totals = new int[] {0};
        Map<String, String> firstFailureMessage = new LinkedHashMap<>();

        try (Stream<Path> stream = Files.list(FIXTURES_DIR)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted()
                    .forEach(file -> {
                        totals[0]++;
                        try {
                            var doc = CsvDocumentParser.parse(file);
                            assertThat(doc.docId()).startsWith("sha256:");
                            assertThat(doc.metadata().pageCount()).isEqualTo(1);
                            assertThat(doc.sections()).hasSize(1);
                            passes[0]++;
                        } catch (ParseException pe) {
                            failureCounts.merge(pe.errorCode(), 1, Integer::sum);
                            firstFailureMessage.putIfAbsent(
                                    pe.errorCode(), file.getFileName() + ": " + pe.getMessage());
                        } catch (RuntimeException re) {
                            // Surface unchecked failures as a synthetic error code so the
                            // distribution is honest about what fell through the cracks.
                            failureCounts.merge("UNCHECKED:" + re.getClass().getSimpleName(), 1, Integer::sum);
                            firstFailureMessage.putIfAbsent(
                                    "UNCHECKED:" + re.getClass().getSimpleName(),
                                    file.getFileName() + ": " + re.getMessage());
                        }
                    });
        }

        Assumptions.assumeTrue(totals[0] > 0, "fixtures/csv contains no .csv files — skipping");

        double passRate = (double) passes[0] / totals[0];
        LOG.info(
                "real-world csv fixture pass rate: {}/{} = {}",
                passes[0],
                totals[0],
                String.format("%.1f%%", passRate * 100));
        if (!failureCounts.isEmpty()) {
            LOG.info("failure distribution by errorCode:");
            failureCounts.forEach((code, count) -> {
                LOG.info("  {} -> {} (e.g. {})", code, count, firstFailureMessage.get(code));
            });
        }

        assertThat(passRate)
                .as("real-world csv pass rate (passes=%d total=%d, failures=%s)", passes[0], totals[0], failureCounts)
                .isGreaterThanOrEqualTo(MIN_PASS_RATE);
    }
}
