package ai.doctruth;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.text.similarity.LevenshteinDistance;

/**
 * Lightweight parser quality metric runner for local benchmark fixtures.
 *
 * @since 1.0.0
 */
public final class ParserBenchmarkRunner {

    private static final LevenshteinDistance LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();

    private ParserBenchmarkRunner() {
        throw new AssertionError("no instances");
    }

    public static List<ParserBenchmarkResult> evaluate(List<ParserBenchmarkCase> cases) {
        Objects.requireNonNull(cases, "cases");
        var results = new ArrayList<ParserBenchmarkResult>(cases.size());
        for (ParserBenchmarkCase benchmarkCase : cases) {
            results.add(evaluateOne(benchmarkCase));
        }
        return List.copyOf(results);
    }

    public static void requireMinimums(List<ParserBenchmarkResult> results, Map<String, Double> minimums) {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(minimums, "minimums");
        var failures = new ArrayList<String>();
        for (ParserBenchmarkResult result : results) {
            minimums.forEach((metric, minimum) -> addFailureIfBelowMinimum(failures, result, metric, minimum));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("parser benchmark thresholds failed: " + String.join("; ", failures));
        }
    }

    public static void requireMaximums(List<ParserBenchmarkResult> results, Map<String, Double> maximums) {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(maximums, "maximums");
        var failures = new ArrayList<String>();
        for (ParserBenchmarkResult result : results) {
            maximums.forEach((metric, maximum) -> addFailureIfAboveMaximum(failures, result, metric, maximum));
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("parser benchmark thresholds failed: " + String.join("; ", failures));
        }
    }

    public static Map<String, Double> aggregateMetrics(List<ParserBenchmarkResult> results) {
        Objects.requireNonNull(results, "results");
        var aggregate = new LinkedHashMap<String, Double>();
        var latencies = results.stream()
                .filter(result -> result.metrics().containsKey("parser_latency_ms"))
                .map(result -> result.metric("parser_latency_ms"))
                .sorted()
                .toList();
        if (!latencies.isEmpty()) {
            aggregate.put("parser_latency_p50", percentile(latencies, 50));
            aggregate.put("parser_latency_p95", percentile(latencies, 95));
        }
        var compactReductions = results.stream()
                .filter(result -> result.metrics().containsKey("compact_llm_size_reduction"))
                .map(result -> result.metric("compact_llm_size_reduction"))
                .toList();
        compactReductions.stream().min(Double::compareTo).ifPresent(value ->
                aggregate.put("compact_llm_size_reduction_min", value));
        return Map.copyOf(aggregate);
    }

    private static ParserBenchmarkResult evaluateOne(ParserBenchmarkCase benchmarkCase) {
        var metrics = new LinkedHashMap<String, Double>();
        metrics.put("reading_order_f1", readingOrderScore(
                benchmarkCase.document().toMarkdownClean(), benchmarkCase.expectedMarkdown()));
        metrics.put("section_boundary_f1", sectionBoundaryF1(
                benchmarkCase.document().toMarkdownClean(), benchmarkCase.expectedMarkdown()));
        metrics.put("quote_anchor_accuracy", quoteAnchorAccuracy(benchmarkCase.document()));
        metrics.put("bbox_coverage", bboxCoverage(benchmarkCase.document()));
        metrics.put("compact_llm_size_reduction", compactLlmSizeReduction(benchmarkCase.document()));
        metrics.put("compact_llm_round_trip", compactLlmRoundTrip(benchmarkCase.document()));
        metrics.put("compact_llm_source_map_coverage", compactLlmSourceMapCoverage(benchmarkCase.document()));
        metrics.put("ocr_text_accuracy", ocrTextAccuracy(benchmarkCase.document(), benchmarkCase.expectedMarkdown()));
        metrics.put("parser_latency_ms", benchmarkCase.parserLatencyMs());
        metrics.put("rss_peak_mb", benchmarkCase.rssPeakMb());
        metrics.put("model_cache_size_mb", benchmarkCase.modelCacheSizeMb());
        benchmarkCase.expectedDocument().ifPresent(expected -> {
            metrics.put("bbox_iou", bboxIou(benchmarkCase.document(), expected));
            metrics.put("table_region_iou", tableRegionIou(benchmarkCase.document(), expected));
            metrics.put("table_cell_f1", tableCellF1(benchmarkCase.document(), expected));
            metrics.put("evidence_span_accuracy", evidenceSpanAccuracy(benchmarkCase.document(), expected));
            metrics.put(
                    "strict_warning_false_negative_rate",
                    strictWarningFalseNegativeRate(benchmarkCase.document(), expected));
        });
        return new ParserBenchmarkResult(
                benchmarkCase.name(),
                benchmarkCase.labelId(),
                benchmarkCase.tags(),
                benchmarkCase.sourceSha256(),
                metrics);
    }

    private static void addFailureIfBelowMinimum(
            List<String> failures, ParserBenchmarkResult result, String metric, Double minimum) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(minimum, "minimum");
        double actual = result.metric(metric);
        if (actual < minimum) {
            failures.add(result.name() + " " + metric + " actual=" + actual + " minimum=" + minimum);
        }
    }

    private static void addFailureIfAboveMaximum(
            List<String> failures, ParserBenchmarkResult result, String metric, Double maximum) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(maximum, "maximum");
        double actual = result.metric(metric);
        if (actual > maximum) {
            failures.add(result.name() + " " + metric + " actual=" + actual + " maximum=" + maximum);
        }
    }

    static void addAggregateFailureIfAboveMaximum(
            List<String> failures, Map<String, Double> aggregate, String metric, Double maximum) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(maximum, "maximum");
        if (!aggregate.containsKey(metric)) {
            return;
        }
        double actual = aggregate.get(metric);
        if (actual > maximum) {
            failures.add("corpus " + metric + " actual=" + actual + " maximum=" + maximum);
        }
    }

    static void addAggregateFailureIfBelowMinimum(
            List<String> failures, Map<String, Double> aggregate, String metric, Double minimum) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(minimum, "minimum");
        if (!aggregate.containsKey(metric)) {
            return;
        }
        double actual = aggregate.get(metric);
        if (actual < minimum) {
            failures.add("corpus " + metric + " actual=" + actual + " minimum=" + minimum);
        }
    }

    private static double percentile(List<Double> sortedValues, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private static double readingOrderScore(String actual, String expected) {
        var actualLines = significantLines(actual);
        var expectedLines = significantLines(expected);
        if (expectedLines.isEmpty()) {
            return actualLines.isEmpty() ? 1.0 : 0.0;
        }
        int lcs = lcsLength(actualLines, expectedLines);
        return lcs / (double) expectedLines.size();
    }

    private static List<String> significantLines(String value) {
        return value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static int lcsLength(List<String> actual, List<String> expected) {
        int[][] dp = new int[actual.size() + 1][expected.size() + 1];
        for (int i = 1; i <= actual.size(); i++) {
            for (int j = 1; j <= expected.size(); j++) {
                if (actual.get(i - 1).equals(expected.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[actual.size()][expected.size()];
    }

    private static double sectionBoundaryF1(String actual, String expected) {
        var actualBoundaries = sectionBoundaries(actual);
        var expectedBoundaries = sectionBoundaries(expected);
        if (expectedBoundaries.isEmpty()) {
            return actualBoundaries.isEmpty() ? 1.0 : 0.0;
        }
        int truePositives = matchedBoundaryCount(actualBoundaries, expectedBoundaries);
        if (truePositives == 0) {
            return 0.0;
        }
        double precision = truePositives / (double) actualBoundaries.size();
        double recall = truePositives / (double) expectedBoundaries.size();
        return 2.0 * precision * recall / (precision + recall);
    }

    private static List<String> sectionBoundaries(String markdown) {
        return significantLines(markdown).stream()
                .filter(ParserBenchmarkRunner::looksLikeSectionBoundary)
                .map(ParserBenchmarkRunner::sectionBoundaryKey)
                .toList();
    }

    private static boolean looksLikeSectionBoundary(String line) {
        var heading = stripMarkdownHeading(line);
        if (heading.isBlank() || heading.length() > 80 || heading.endsWith(".")) {
            return false;
        }
        if (line.stripLeading().startsWith("#")) {
            return true;
        }
        return hasLetter(heading) && heading.equals(heading.toUpperCase(java.util.Locale.ROOT));
    }

    private static String stripMarkdownHeading(String line) {
        return line.strip().replaceFirst("^#{1,6}\\s+", "").strip();
    }

    private static boolean hasLetter(String value) {
        return value.codePoints().anyMatch(Character::isLetter);
    }

    private static String sectionBoundaryKey(String line) {
        return normalizeText(stripMarkdownHeading(line));
    }

    private static int matchedBoundaryCount(List<String> actual, List<String> expected) {
        var unmatched = new ArrayList<>(expected);
        int matches = 0;
        for (String key : actual) {
            int index = unmatched.indexOf(key);
            if (index >= 0) {
                matches++;
                unmatched.remove(index);
            }
        }
        return matches;
    }

    private static double quoteAnchorAccuracy(TrustDocument document) {
        if (document.body().units().isEmpty()) {
            return 1.0;
        }
        long anchored = document.body().units().stream()
                .filter(unit -> !unit.evidence().evidenceSpanIds().isEmpty())
                .count();
        return anchored / (double) document.body().units().size();
    }

    private static double bboxCoverage(TrustDocument document) {
        if (document.body().units().isEmpty()) {
            return 1.0;
        }
        long withBbox = document.body().units().stream()
                .filter(unit -> unit.location().boundingBox().isPresent())
                .count();
        return withBbox / (double) document.body().units().size();
    }

    private static double compactLlmSizeReduction(TrustDocument document) {
        long fullBytes = jsonFullByteLength(document);
        if (fullBytes == 0) {
            return 1.0;
        }
        long compactBytes = compactLlmByteLength(document);
        return Math.max(0.0, 1.0 - compactBytes / (double) fullBytes);
    }

    static long jsonFullByteLength(TrustDocument document) {
        return byteLength(writer -> document.writeJsonFull(writer));
    }

    static long compactLlmByteLength(TrustDocument document) {
        return byteLength(writer -> document.writeCompactLlm(writer));
    }

    private static long byteLength(ByteWritingOperation operation) {
        var out = new CountingOutputStream();
        try (var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            operation.write(writer);
        } catch (IOException e) {
            throw new IllegalStateException("failed to count rendered TrustDocument bytes", e);
        }
        return out.bytes();
    }

    private static double compactLlmRoundTrip(TrustDocument document) {
        var rendered = document.toCompactLlmWithSourceMap();
        if (!rendered.text().equals(document.toCompactLlm())) {
            return 0.0;
        }
        return rendered.sourceMap().stream().allMatch(entry -> validRange(rendered.text(), entry)) ? 1.0 : 0.0;
    }

    private static double compactLlmSourceMapCoverage(TrustDocument document) {
        var citeable = citeableUnitIds(document);
        if (citeable.isEmpty()) {
            return 1.0;
        }
        var mapped = document.toCompactLlmWithSourceMap().sourceMap().stream()
                .filter(entry -> !entry.evidenceSpanIds().isEmpty())
                .map(TrustSourceMapEntry::unitId)
                .collect(Collectors.toSet());
        long covered = citeable.stream().filter(mapped::contains).count();
        return covered / (double) citeable.size();
    }

    private static boolean validRange(String text, TrustSourceMapEntry entry) {
        return entry.endOffset() <= text.length() && entry.startOffset() < entry.endOffset();
    }

    private static Set<String> citeableUnitIds(TrustDocument document) {
        return document.body().units().stream()
                .filter(unit -> !unit.content().text().isBlank())
                .filter(unit -> !unit.evidence().evidenceSpanIds().isEmpty())
                .map(TrustUnit::unitId)
                .collect(Collectors.toSet());
    }

    private static double ocrTextAccuracy(TrustDocument document, String expectedMarkdown) {
        var ocrText = document.body().units().stream()
                .filter(unit -> unit.kind() == TrustUnitKind.OCR_REGION)
                .map(unit -> unit.content().text())
                .collect(Collectors.joining("\n"));
        if (ocrText.isBlank()) {
            return 1.0;
        }
        return normalizedTextAccuracy(ocrText, expectedMarkdown);
    }

    private static double normalizedTextAccuracy(String actual, String expected) {
        var normalizedActual = normalizeText(actual);
        var normalizedExpected = normalizeText(expected);
        if (normalizedExpected.isEmpty()) {
            return normalizedActual.isEmpty() ? 1.0 : 0.0;
        }
        int distance = LEVENSHTEIN.apply(normalizedActual, normalizedExpected);
        return Math.max(0.0, 1.0 - distance / (double) normalizedExpected.length());
    }

    private static String normalizeText(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private static double bboxIou(TrustDocument actual, TrustDocument expected) {
        var actualBoxes = actual.body().units().stream()
                .map(unit -> unit.location().boundingBox())
                .flatMap(java.util.Optional::stream)
                .toList();
        var expectedBoxes = expected.body().units().stream()
                .map(unit -> unit.location().boundingBox())
                .flatMap(java.util.Optional::stream)
                .toList();
        if (expectedBoxes.isEmpty()) {
            return actualBoxes.isEmpty() ? 1.0 : 0.0;
        }
        int pairs = Math.min(actualBoxes.size(), expectedBoxes.size());
        if (pairs == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < pairs; i++) {
            total += iou(actualBoxes.get(i), expectedBoxes.get(i));
        }
        return total / expectedBoxes.size();
    }

    private static double tableRegionIou(TrustDocument actual, TrustDocument expected) {
        var actualBoxes = actual.body().tables().stream()
                .map(TrustTable::boundingBox)
                .flatMap(java.util.Optional::stream)
                .toList();
        var expectedBoxes = expected.body().tables().stream()
                .map(TrustTable::boundingBox)
                .flatMap(java.util.Optional::stream)
                .toList();
        if (expectedBoxes.isEmpty()) {
            return actualBoxes.isEmpty() ? 1.0 : 0.0;
        }
        int pairs = Math.min(actualBoxes.size(), expectedBoxes.size());
        if (pairs == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < pairs; i++) {
            total += iou(actualBoxes.get(i), expectedBoxes.get(i));
        }
        return total / expectedBoxes.size();
    }

    private static double tableCellF1(TrustDocument actual, TrustDocument expected) {
        var actualCells = tableCellKeys(actual);
        var expectedCells = tableCellKeys(expected);
        if (expectedCells.isEmpty()) {
            return actualCells.isEmpty() ? 1.0 : 0.0;
        }
        long truePositives = actualCells.stream().filter(expectedCells::contains).count();
        if (truePositives == 0) {
            return 0.0;
        }
        double precision = truePositives / (double) actualCells.size();
        double recall = truePositives / (double) expectedCells.size();
        return 2.0 * precision * recall / (precision + recall);
    }

    private static double evidenceSpanAccuracy(TrustDocument actual, TrustDocument expected) {
        var expectedLines = expectedEvidenceLines(expected);
        if (expectedLines.isEmpty()) {
            return actualEvidenceLines(actual).isEmpty() ? 1.0 : 0.0;
        }
        var unmatched = new ArrayList<>(actualEvidenceLines(actual));
        int correct = 0;
        for (String expectedLine : expectedLines) {
            int index = unmatched.indexOf(expectedLine);
            if (index >= 0) {
                correct++;
                unmatched.remove(index);
            }
        }
        return correct / (double) expectedLines.size();
    }

    private static List<String> expectedEvidenceLines(TrustDocument document) {
        return document.body().units().stream()
                .filter(unit -> !unit.content().text().isBlank())
                .flatMap(unit -> significantLines(unit.content().text()).stream())
                .map(ParserBenchmarkRunner::normalizeText)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static List<String> actualEvidenceLines(TrustDocument document) {
        return document.body().units().stream()
                .filter(unit -> !unit.evidence().evidenceSpanIds().isEmpty())
                .flatMap(unit -> significantLines(unit.content().text()).stream())
                .map(ParserBenchmarkRunner::normalizeText)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static double strictWarningFalseNegativeRate(TrustDocument actual, TrustDocument expected) {
        var expectedWarnings = severeWarningCodes(expected);
        if (expectedWarnings.isEmpty()) {
            return 0.0;
        }
        var actualWarnings = severeWarningCodes(actual);
        long missed = expectedWarnings.stream().filter(code -> !actualWarnings.contains(code)).count();
        return missed / (double) expectedWarnings.size();
    }

    private static Set<String> severeWarningCodes(TrustDocument document) {
        var codes = document.parserRun().warnings().stream()
                .filter(warning -> warning.severity() == ParserWarningSeverity.SEVERE)
                .map(ParserWarning::code)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        document.body().units().stream()
                .flatMap(unit -> unit.evidence().warnings().stream())
                .filter(warning -> warning.severity() == ParserWarningSeverity.SEVERE)
                .map(ParserWarning::code)
                .forEach(codes::add);
        return codes;
    }

    private static List<String> tableCellKeys(TrustDocument document) {
        return document.body().tables().stream()
                .flatMap(table -> table.cells().stream())
                .map(cell -> cell.rowRange().start()
                        + ":"
                        + cell.rowRange().end()
                        + ":"
                        + cell.columnRange().start()
                        + ":"
                        + cell.columnRange().end()
                        + ":"
                        + cell.text().strip())
                .toList();
    }

    private static double iou(BoundingBox actual, BoundingBox expected) {
        double x0 = Math.max(actual.x0(), expected.x0());
        double y0 = Math.max(actual.y0(), expected.y0());
        double x1 = Math.min(actual.x1(), expected.x1());
        double y1 = Math.min(actual.y1(), expected.y1());
        double intersection = area(x0, y0, x1, y1);
        double union = area(actual.x0(), actual.y0(), actual.x1(), actual.y1())
                + area(expected.x0(), expected.y0(), expected.x1(), expected.y1())
                - intersection;
        return union <= 0.0 ? 0.0 : intersection / union;
    }

    private static double area(double x0, double y0, double x1, double y1) {
        return Math.max(0.0, x1 - x0) * Math.max(0.0, y1 - y0);
    }

    @FunctionalInterface
    private interface ByteWritingOperation {
        void write(Writer writer) throws IOException;
    }

    private static final class CountingOutputStream extends OutputStream {
        private long bytes;

        long bytes() {
            return bytes;
        }

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }
}
