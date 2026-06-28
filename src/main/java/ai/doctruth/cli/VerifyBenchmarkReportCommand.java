package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class VerifyBenchmarkReportCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPORT_FORMAT = "doctruth.parser-benchmark.report.v1";

    private final CliContext context;

    VerifyBenchmarkReportCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var report = readJson(options.report(), "benchmark report");
        verifyFormat(report);
        var manifest = Path.of(requiredText(report, "manifest"));
        verifyManifestHash(report, manifest);
        verifyManifestEcho(report, readJson(manifest, "benchmark manifest"));
        verifyExternalMetrics(report, manifest);
        verifyValidityInputs(report);
        verifyCoverageCounts(report);
        verifyCaseReplay(report);
        verifyAggregateMetrics(report);
        verifyMetricThresholds(report);
        context.out().println("benchmark report verified");
    }

    private static void verifyFormat(JsonNode report) throws CliException {
        String format = requiredText(report, "reportFormat");
        if (!REPORT_FORMAT.equals(format)) {
            throw new CliException("unsupported benchmark report format: " + format);
        }
        if (!report.path("passed").asBoolean(false)) {
            throw new CliException("benchmark report did not pass");
        }
    }

    private static void verifyManifestHash(JsonNode report, Path manifest) throws CliException {
        String expected = requiredText(report, "manifestSha256");
        String actual = sha256(manifest, "benchmark manifest");
        if (!expected.equals(actual)) {
            throw new CliException("manifestSha256 mismatch: expected " + expected + " actual " + actual);
        }
    }

    private static void verifyManifestEcho(JsonNode report, JsonNode manifest) throws CliException {
        compareText(report, manifest, "corpus", "name");
        compareObject(report, manifest, "minimums");
        compareObject(report, manifest, "maximums");
        compareObject(report, manifest, "externalEvaluations");
        compareArray(report, manifest.path("labeling"), "requiredMetrics");
        compareArray(report, manifest.path("labeling"), "requiredTags");
        compareArray(report, manifest.path("labeling"), "requiredFixtureTypes");
        compareArray(report, manifest.path("labeling"), "requiredBehaviors");
        compareMinCasesPerTag(report, manifest.path("labeling"));
        compareExpandedMinimum(report, manifest.path("labeling"), "minCasesPerFixtureType", "requiredFixtureTypes");
        compareExpandedMinimum(report, manifest.path("labeling"), "minCasesPerBehavior", "requiredBehaviors");
        compareOptionalValue(report, manifest.path("labeling"), "minTotalCases");
        verifyCaseSourcePins(report, manifest);
    }

    private static void verifyCaseSourcePins(JsonNode report, JsonNode manifest) throws CliException {
        var pins = new LinkedHashMap<String, String>();
        for (JsonNode node : manifest.path("cases")) {
            String name = node.path("name").asText();
            String sourceSha = node.path("sourceSha256").asText();
            if (!name.isBlank() && !sourceSha.isBlank()) {
                pins.put(name, sourceSha);
            }
        }
        for (JsonNode node : report.path("cases")) {
            String name = node.path("name").asText();
            String expected = pins.get(name);
            if (expected == null) {
                continue;
            }
            String actual = node.path("sourceSha256").asText();
            if (!expected.equals(actual)) {
                throw new CliException("sourceSha256 mismatch for case " + name);
            }
        }
    }

    private static void verifyCoverageCounts(JsonNode report) throws CliException {
        int actualCaseCount = report.path("cases").size();
        int recordedCaseCount = report.path("caseCount").asInt(-1);
        if (recordedCaseCount != actualCaseCount) {
            throw new CliException("caseCount mismatch: expected " + recordedCaseCount + " actual " + actualCaseCount);
        }
        var actualCasesPerTag = casesPerTag(report);
        var recordedCasesPerTag = report.path("casesPerTag");
        var recordedCounts = integerObject(recordedCasesPerTag);
        if (!recordedCounts.equals(actualCasesPerTag)) {
            throw new CliException("casesPerTag mismatch: expected " + recordedCounts + " actual " + actualCasesPerTag);
        }
        var coverageRequired = integerObject(report.path("coverageRequired"));
        if (!coverageRequired.equals(integerObject(report.path("minCasesPerTag")))) {
            throw new CliException("coverageRequired mismatch");
        }
        var expectedSatisfied = coverageSatisfied(coverageRequired, actualCasesPerTag);
        if (!expectedSatisfied.equals(booleanObject(report.path("coverageSatisfied"), "coverageSatisfied"))) {
            throw new CliException("coverageSatisfied mismatch");
        }
        verifyCoverageMap(
                report, "fixtureTypes", "casesPerFixtureType", "fixtureCoverageRequired", "fixtureCoverageSatisfied");
        verifyCoverageMap(
                report, "behaviors", "casesPerBehavior", "behaviorCoverageRequired", "behaviorCoverageSatisfied");
        verifyCoverageThresholds(report, actualCaseCount, actualCasesPerTag);
    }

    private static void verifyCoverageMap(
            JsonNode report, String caseField, String countField, String requiredField, String satisfiedField)
            throws CliException {
        var actual = casesPerField(report, caseField);
        if (!integerObject(report.path(countField)).equals(actual)) {
            throw new CliException(countField + " mismatch");
        }
        var required = integerObject(report.path(requiredField));
        var expectedSatisfied = coverageSatisfied(required, actual);
        if (!expectedSatisfied.equals(booleanObject(report.path(satisfiedField), satisfiedField))) {
            throw new CliException(satisfiedField + " mismatch");
        }
    }

    private static void verifyCoverageThresholds(
            JsonNode report, int actualCaseCount, Map<String, Integer> actualCasesPerTag) throws CliException {
        JsonNode minTotalCases = report.path("minTotalCases");
        if (minTotalCases.isInt() && actualCaseCount < minTotalCases.asInt()) {
            throw new CliException(
                    "minTotalCases not satisfied: minimum " + minTotalCases.asInt() + " actual " + actualCaseCount);
        }
        for (var entry : report.path("minCasesPerTag").properties()) {
            int minimum = entry.getValue().asInt(-1);
            int actual = actualCasesPerTag.getOrDefault(entry.getKey(), 0);
            if (minimum >= 0 && actual < minimum) {
                throw new CliException("minCasesPerTag not satisfied for " + entry.getKey() + ": minimum " + minimum
                        + " actual " + actual);
            }
        }
    }

    private static Map<String, Integer> casesPerTag(JsonNode report) {
        return casesPerField(report, "tags");
    }

    private static Map<String, Integer> casesPerField(JsonNode report, String field) {
        var counts = new LinkedHashMap<String, Integer>();
        for (JsonNode caseNode : report.path("cases")) {
            for (JsonNode tagNode : caseNode.path(field)) {
                String tag = tagNode.asText();
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Boolean> coverageSatisfied(
            Map<String, Integer> minimums, Map<String, Integer> actualCasesPerTag) {
        var values = new LinkedHashMap<String, Boolean>();
        minimums.forEach((tag, minimum) -> values.put(tag, actualCasesPerTag.getOrDefault(tag, 0) >= minimum));
        return values;
    }

    private static Map<String, Integer> integerObject(JsonNode node) throws CliException {
        if (!node.isObject()) {
            throw new CliException("casesPerTag mismatch: expected object actual " + node.getNodeType());
        }
        var values = new LinkedHashMap<String, Integer>();
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (!value.canConvertToInt()) {
                throw new CliException("casesPerTag mismatch for " + entry.getKey() + ": expected integer");
            }
            values.put(entry.getKey(), value.asInt());
        }
        return values;
    }

    private static Map<String, Boolean> booleanObject(JsonNode node, String field) throws CliException {
        if (!node.isObject()) {
            throw new CliException(field + " mismatch: expected object actual " + node.getNodeType());
        }
        var values = new LinkedHashMap<String, Boolean>();
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (!value.isBoolean()) {
                throw new CliException(field + " mismatch for " + entry.getKey() + ": expected boolean");
            }
            values.put(entry.getKey(), value.asBoolean());
        }
        return values;
    }

    private static void verifyValidityInputs(JsonNode report) throws CliException {
        var expected = new LinkedHashMap<String, Object>();
        expected.put("sourceHashes", true);
        expected.put("manifestHash", true);
        expected.put("parserConfig", "TrustDocument");
        expected.put("modelCacheManifest", "not-required");
        expected.put("thresholds", true);
        expected.put("expectedLabels", true);
        expected.put("actualTrustDocument", true);
        if (!objectEquals(report.path("validityInputs"), expected)) {
            throw new CliException("validityInputs mismatch");
        }
    }

    private static boolean objectEquals(JsonNode node, Map<String, Object> expected) {
        if (!node.isObject() || node.size() != expected.size()) {
            return false;
        }
        for (var entry : expected.entrySet()) {
            JsonNode actual = node.path(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Boolean bool && (!actual.isBoolean() || actual.asBoolean() != bool)) {
                return false;
            }
            if (value instanceof String text && !actual.asText().equals(text)) {
                return false;
            }
        }
        return true;
    }

    private static void verifyCaseReplay(JsonNode report) throws CliException {
        for (JsonNode caseNode : report.path("cases")) {
            verifyReplayFlag(
                    caseNode,
                    "sourceRefReplayable",
                    !caseNode.path("sourceSha256").asText().isBlank());
            verifyReplayFlag(
                    caseNode,
                    "quoteReplayable",
                    caseNode.path("metrics").path("quote_anchor_accuracy").asDouble(0.0) >= 1.0);
            verifyReplayFlag(
                    caseNode,
                    "evidenceSpanReplayable",
                    caseNode.path("metrics").path("evidence_span_accuracy").asDouble(0.0) >= 1.0);
        }
    }

    private static void verifyReplayFlag(JsonNode caseNode, String field, boolean expected) throws CliException {
        JsonNode replay = caseNode.path("replay");
        if (!replay.isObject()
                || !replay.path(field).isBoolean()
                || replay.path(field).asBoolean() != expected) {
            throw new CliException(
                    "case replay mismatch for " + caseNode.path("name").asText() + ": " + field);
        }
    }

    private static void verifyAggregateMetrics(JsonNode report) throws CliException {
        verifyPercentileMetric(report, "parser_latency_p50", "parser_latency_ms", 50);
        verifyPercentileMetric(report, "parser_latency_p95", "parser_latency_ms", 95);
        verifyMinimumAggregateMetric(report, "compact_llm_size_reduction_min", "compact_llm_size_reduction");
    }

    private static void verifyPercentileMetric(
            JsonNode report, String aggregateMetric, String caseMetric, int percentile) throws CliException {
        JsonNode aggregate = report.path("metrics").path(aggregateMetric);
        if (!aggregate.isNumber()) {
            return;
        }
        var values = caseMetricValues(report, caseMetric);
        if (values.isEmpty()) {
            throw new CliException("aggregate metric mismatch for " + aggregateMetric + ": missing case metrics");
        }
        values.sort(Double::compareTo);
        int index = (int) Math.ceil((percentile / 100.0) * values.size()) - 1;
        double expected = values.get(Math.max(0, Math.min(values.size() - 1, index)));
        assertCloseAggregate(aggregateMetric, aggregate.asDouble(), expected);
    }

    private static void verifyMinimumAggregateMetric(JsonNode report, String aggregateMetric, String caseMetric)
            throws CliException {
        JsonNode aggregate = report.path("metrics").path(aggregateMetric);
        if (!aggregate.isNumber()) {
            return;
        }
        var values = caseMetricValues(report, caseMetric);
        if (values.isEmpty()) {
            throw new CliException("aggregate metric mismatch for " + aggregateMetric + ": missing case metrics");
        }
        double expected = values.stream().min(Double::compareTo).orElse(Double.NaN);
        assertCloseAggregate(aggregateMetric, aggregate.asDouble(), expected);
    }

    private static java.util.List<Double> caseMetricValues(JsonNode report, String metric) {
        var values = new java.util.ArrayList<Double>();
        for (JsonNode caseNode : report.path("cases")) {
            JsonNode value = caseNode.path("metrics").path(metric);
            if (value.isNumber()) {
                values.add(value.asDouble());
            }
        }
        return values;
    }

    private static void assertCloseAggregate(String metric, double actual, double expected) throws CliException {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 0.000001) {
            throw new CliException(
                    "aggregate metric mismatch for " + metric + ": expected " + expected + " actual " + actual);
        }
    }

    private static void verifyMetricThresholds(JsonNode report) throws CliException {
        JsonNode metrics = report.path("metrics");
        if (!metrics.isObject()) {
            throw new CliException("benchmark report missing metrics");
        }
        for (var entry : report.path("minimums").properties()) {
            String name = entry.getKey();
            double minimum = entry.getValue().asDouble(Double.NaN);
            for (double actual : metricValues(report, name)) {
                if (!Double.isFinite(actual) || actual < minimum) {
                    throw new CliException(
                            "minimum threshold failed for " + name + ": minimum " + minimum + " actual " + actual);
                }
            }
        }
        for (var entry : report.path("maximums").properties()) {
            String name = entry.getKey();
            double maximum = entry.getValue().asDouble(Double.NaN);
            for (double actual : metricValues(report, name)) {
                if (!Double.isFinite(actual) || actual > maximum) {
                    throw new CliException(
                            "maximum threshold failed for " + name + ": maximum " + maximum + " actual " + actual);
                }
            }
        }
    }

    private static void verifyExternalMetrics(JsonNode report, Path manifestPath) throws CliException {
        var manifest = readJson(manifestPath, "benchmark manifest");
        JsonNode externalEvaluations = manifest.path("externalEvaluations");
        if (externalEvaluations.isMissingNode() || externalEvaluations.isNull()) {
            return;
        }
        if (!externalEvaluations.isObject()) {
            throw new CliException("externalEvaluations mismatch");
        }
        if (!report.path("metrics").isObject()) {
            throw new CliException("benchmark report missing metrics");
        }
        Path base = manifestPath.toAbsolutePath().getParent();
        for (var entry : externalEvaluations.properties()) {
            String name = entry.getKey();
            if (!"opendataloader".equals(name)) {
                throw new CliException("unsupported external evaluation: " + name);
            }
            Path evaluation = base.resolve(entry.getValue().asText()).normalize();
            var expected = openDataLoaderExternalMetrics(evaluation);
            if (!report.path("externalMetrics").path(name).equals(expected.node())) {
                throw new CliException("external metrics mismatch for " + name);
            }
            for (var metric : expected.values().entrySet()) {
                double actual = report.path("metrics").path(metric.getKey()).asDouble(Double.NaN);
                if (!Double.isFinite(actual) || Math.abs(actual - metric.getValue()) > 0.000001) {
                    throw new CliException("external metrics mismatch for " + metric.getKey());
                }
            }
        }
    }

    private static ExternalMetricSet openDataLoaderExternalMetrics(Path path) throws CliException {
        JsonNode root = readJson(path, "OpenDataLoader evaluation");
        var node = MAPPER.createObjectNode();
        var values = new LinkedHashMap<String, Double>();
        putExternalMetric(
                node,
                values,
                "nid",
                "opendataloader_nid",
                root.path("metrics").path("score").path("nid_mean"));
        putExternalMetric(
                node,
                values,
                "teds",
                "opendataloader_teds",
                root.path("metrics").path("score").path("teds_mean"));
        putExternalMetric(
                node,
                values,
                "mhs",
                "opendataloader_mhs",
                root.path("metrics").path("score").path("mhs_mean"));
        JsonNode speed = root.path("speed").path("elapsed_per_doc");
        putExternalMetric(
                node,
                values,
                "speed",
                "opendataloader_speed",
                speed.isNumber() ? speed : root.path("summary").path("elapsed_per_doc"));
        node.put("evaluationSha256", sha256(path, "OpenDataLoader evaluation"));
        return new ExternalMetricSet(node, values);
    }

    private static void putExternalMetric(
            com.fasterxml.jackson.databind.node.ObjectNode node,
            Map<String, Double> values,
            String field,
            String key,
            JsonNode metric) {
        if (!metric.isNumber()) {
            return;
        }
        double value = metric.asDouble();
        node.put(field, value);
        values.put(key, value);
    }

    private static java.util.List<Double> metricValues(JsonNode report, String name) {
        JsonNode aggregate = report.path("metrics").path(name);
        if (aggregate.isNumber()) {
            return java.util.List.of(aggregate.asDouble());
        }
        var values = new java.util.ArrayList<Double>();
        for (JsonNode caseNode : report.path("cases")) {
            JsonNode value = caseNode.path("metrics").path(name);
            if (value.isNumber()) {
                values.add(value.asDouble());
            }
        }
        return values.isEmpty() ? java.util.List.of(Double.NaN) : values;
    }

    private static void compareText(JsonNode report, JsonNode manifest, String reportField, String manifestField)
            throws CliException {
        String left = requiredText(report, reportField);
        String right = requiredText(manifest, manifestField);
        if (!left.equals(right)) {
            throw new CliException(reportField + " mismatch: expected " + left + " actual " + right);
        }
    }

    private static void compareObject(JsonNode report, JsonNode manifest, String field) throws CliException {
        JsonNode left = report.path(field);
        JsonNode right = manifest.path(field).isMissingNode() ? MAPPER.createObjectNode() : manifest.path(field);
        if (!left.isObject() || !right.isObject() || !left.equals(right)) {
            throw new CliException(field + " mismatch");
        }
    }

    private static void compareArray(JsonNode report, JsonNode manifestLabeling, String field) throws CliException {
        JsonNode left = report.path(field);
        JsonNode right = manifestLabeling.path(field);
        if (right.isMissingNode()) {
            return;
        }
        if (!left.isArray() || !left.equals(right)) {
            throw new CliException(field + " mismatch");
        }
    }

    private static void compareMinCasesPerTag(JsonNode report, JsonNode manifestLabeling) throws CliException {
        JsonNode manifestMinimum = manifestLabeling.path("minCasesPerTag");
        if (manifestMinimum.isMissingNode()) {
            return;
        }
        JsonNode expected = expectedMinCasesPerTag(manifestLabeling, manifestMinimum);
        JsonNode actual = report.path("minCasesPerTag");
        if (!actual.isObject() || !actual.equals(expected)) {
            throw new CliException("minCasesPerTag mismatch");
        }
    }

    private static void compareExpandedMinimum(
            JsonNode report, JsonNode manifestLabeling, String minimumField, String requiredField) throws CliException {
        JsonNode manifestMinimum = manifestLabeling.path(minimumField);
        if (manifestMinimum.isMissingNode()) {
            return;
        }
        JsonNode expected = expectedMinimums(manifestLabeling, manifestMinimum, requiredField);
        JsonNode actual = report.path(minimumField);
        if (!actual.isObject() || !actual.equals(expected)) {
            throw new CliException(minimumField + " mismatch");
        }
    }

    private static JsonNode expectedMinCasesPerTag(JsonNode manifestLabeling, JsonNode manifestMinimum) {
        return expectedMinimums(manifestLabeling, manifestMinimum, "requiredTags");
    }

    private static JsonNode expectedMinimums(
            JsonNode manifestLabeling, JsonNode manifestMinimum, String requiredField) {
        if (manifestMinimum.isObject()) {
            return manifestMinimum;
        }
        var expected = MAPPER.createObjectNode();
        if (!manifestMinimum.isInt()) {
            return expected;
        }
        for (JsonNode tag : manifestLabeling.path(requiredField)) {
            String name = tag.asText();
            if (!name.isBlank()) {
                expected.put(name, manifestMinimum.asInt());
            }
        }
        return expected;
    }

    private static void compareOptionalValue(JsonNode report, JsonNode manifestLabeling, String field)
            throws CliException {
        JsonNode right = manifestLabeling.path(field);
        if (right.isMissingNode()) {
            return;
        }
        JsonNode left = report.path(field);
        if (!left.equals(right)) {
            throw new CliException(field + " mismatch");
        }
    }

    private static JsonNode readJson(Path path, String label) throws CliException {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new CliException("failed to read " + label + ": " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field) throws CliException {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new CliException("benchmark report missing " + field);
        }
        return value;
    }

    private static String sha256(Path path, String label) throws CliException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new CliException("failed to hash " + label + ": " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new CliException("SHA-256 is unavailable", e);
        }
    }

    private record Options(Path report) {
        static Options parse(String[] args) {
            if (args.length != 2) {
                throw new UsageException("usage: doctruth verify-benchmark-report <report.json>");
            }
            return new Options(Path.of(args[1]));
        }
    }

    private record ExternalMetricSet(JsonNode node, Map<String, Double> values) {}
}
