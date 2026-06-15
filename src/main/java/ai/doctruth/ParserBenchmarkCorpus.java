package ai.doctruth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Labeled parser benchmark corpus loaded from a JSON manifest.
 *
 * @since 1.0.0
 */
public final class ParserBenchmarkCorpus {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> HUMAN_REVIEWED_PARSER_ACCURACY_METRICS = List.of(
            "reading_order_f1",
            "quote_anchor_accuracy",
            "bbox_coverage",
            "bbox_iou",
            "evidence_span_accuracy",
            "table_cell_f1",
            "ocr_text_accuracy");
    private static final List<String> HUMAN_REVIEWED_PARSER_ACCURACY_TAGS =
            List.of("multi-layout", "table", "ocr", "bbox", "source-map");

    private final String name;
    private final String kind;
    private final Optional<String> qualityProfile;
    private final Optional<String> labelSetVersion;
    private final Optional<String> reviewType;
    private final List<String> requiredMetrics;
    private final List<String> requiredTags;
    private final Map<String, Integer> minCasesPerTag;
    private final List<String> requiredFixtureTypes;
    private final Map<String, Integer> minCasesPerFixtureType;
    private final List<String> requiredBehaviors;
    private final Map<String, Integer> minCasesPerBehavior;
    private final Optional<Integer> minTotalCases;
    private final List<ParserBenchmarkCase> cases;
    private final Map<String, Double> minimums;
    private final Map<String, Double> maximums;
    private final Map<String, String> externalEvaluations;
    private final Map<String, Map<String, Object>> externalMetrics;
    private final Map<String, Double> externalMetricValues;

    private ParserBenchmarkCorpus(
            String name,
            String kind,
            Optional<String> qualityProfile,
            Optional<String> labelSetVersion,
            Optional<String> reviewType,
            List<String> requiredMetrics,
            List<String> requiredTags,
            Map<String, Integer> minCasesPerTag,
            List<String> requiredFixtureTypes,
            Map<String, Integer> minCasesPerFixtureType,
            List<String> requiredBehaviors,
            Map<String, Integer> minCasesPerBehavior,
            Optional<Integer> minTotalCases,
            List<ParserBenchmarkCase> cases,
            Map<String, Double> minimums,
            Map<String, Double> maximums,
            Map<String, String> externalEvaluations,
            Map<String, Map<String, Object>> externalMetrics,
            Map<String, Double> externalMetricValues) {
        this.name = name;
        this.kind = kind;
        this.qualityProfile = qualityProfile;
        this.labelSetVersion = labelSetVersion;
        this.reviewType = reviewType;
        this.requiredMetrics = List.copyOf(requiredMetrics);
        this.requiredTags = List.copyOf(requiredTags);
        this.minCasesPerTag = Map.copyOf(minCasesPerTag);
        this.requiredFixtureTypes = List.copyOf(requiredFixtureTypes);
        this.minCasesPerFixtureType = Map.copyOf(minCasesPerFixtureType);
        this.requiredBehaviors = List.copyOf(requiredBehaviors);
        this.minCasesPerBehavior = Map.copyOf(minCasesPerBehavior);
        this.minTotalCases = Objects.requireNonNull(minTotalCases, "minTotalCases");
        this.cases = List.copyOf(cases);
        this.minimums = Map.copyOf(minimums);
        this.maximums = Map.copyOf(maximums);
        this.externalEvaluations = Map.copyOf(externalEvaluations);
        this.externalMetrics = Map.copyOf(externalMetrics);
        this.externalMetricValues = Map.copyOf(externalMetricValues);
    }

    public static ParserBenchmarkCorpus load(Path manifestPath) {
        return load(manifestPath, false);
    }

    public static ParserBenchmarkCorpus load(Path manifestPath, boolean offline) {
        Objects.requireNonNull(manifestPath, "manifestPath");
        try {
            JsonNode root = MAPPER.readTree(Files.readString(manifestPath));
            var base = manifestPath.toAbsolutePath().getParent();
            var minimums = thresholds(root, "minimums");
            var maximums = thresholds(root, "maximums");
            var external = externalMetrics(base, root);
            var nodes = root.path("cases");
            var qualityProfile = optionalText(root, "qualityProfile");
            var labeling = labeling(root, qualityProfile, nodes, minimums, maximums);
            return new ParserBenchmarkCorpus(
                    text(root, "name"),
                    labeling.kind(),
                    qualityProfile,
                    labeling.labelSetVersion(),
                    labeling.reviewType(),
                    labeling.requiredMetrics(),
                    labeling.requiredTags(),
                    labeling.minCasesPerTag(),
                    labeling.requiredFixtureTypes(),
                    labeling.minCasesPerFixtureType(),
                    labeling.requiredBehaviors(),
                    labeling.minCasesPerBehavior(),
                    labeling.minTotalCases(),
                    cases(base, nodes, offline),
                    minimums,
                    maximums,
                    external.evaluations(),
                    external.metrics(),
                    external.values());
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid parser benchmark corpus manifest: " + manifestPath, e);
        }
    }

    public String name() {
        return name;
    }

    public String kind() {
        return kind;
    }

    public Optional<String> labelSetVersion() {
        return labelSetVersion;
    }

    public Optional<String> reviewType() {
        return reviewType;
    }

    public Optional<String> qualityProfile() {
        return qualityProfile;
    }

    public List<String> requiredMetrics() {
        return requiredMetrics;
    }

    public List<String> requiredTags() {
        return requiredTags;
    }

    public Map<String, Integer> minCasesPerTag() {
        return minCasesPerTag;
    }

    public List<String> requiredFixtureTypes() {
        return requiredFixtureTypes;
    }

    public Map<String, Integer> minCasesPerFixtureType() {
        return minCasesPerFixtureType;
    }

    public List<String> requiredBehaviors() {
        return requiredBehaviors;
    }

    public Map<String, Integer> minCasesPerBehavior() {
        return minCasesPerBehavior;
    }

    public Optional<Integer> minTotalCases() {
        return minTotalCases;
    }

    public List<ParserBenchmarkCase> cases() {
        return cases;
    }

    public Map<String, Double> minimums() {
        return minimums;
    }

    public Map<String, Double> maximums() {
        return maximums;
    }

    public Map<String, String> externalEvaluations() {
        return externalEvaluations;
    }

    public Map<String, Map<String, Object>> externalMetrics() {
        return externalMetrics;
    }

    public Map<String, Double> externalMetricValues() {
        return externalMetricValues;
    }

    public List<ParserBenchmarkResult> evaluate() {
        return ParserBenchmarkRunner.evaluate(cases);
    }

    public Map<String, Double> aggregateMetrics() {
        return mergedMetrics(ParserBenchmarkRunner.aggregateMetrics(evaluate()), externalMetricValues);
    }

    public void requireMinimums() {
        requireThresholds();
    }

    public void requireThresholds() {
        var results = evaluate();
        var aggregate = mergedMetrics(ParserBenchmarkRunner.aggregateMetrics(results), externalMetricValues);
        var aggregateMinimums = selectAggregateThresholds(minimums, aggregate);
        requireAggregateMinimums(aggregate, aggregateMinimums);
        ParserBenchmarkRunner.requireMinimums(results, withoutKeys(minimums, aggregateMinimums));
        var aggregateMaximums = selectAggregateThresholds(maximums, aggregate);
        requireAggregateMaximums(aggregate, aggregateMaximums);
        ParserBenchmarkRunner.requireMaximums(results, withoutKeys(maximums, aggregateMaximums));
    }

    private static void requireAggregateMinimums(Map<String, Double> aggregate, Map<String, Double> minimums) {
        var failures = new ArrayList<String>();
        minimums.forEach((metric, minimum) ->
                ParserBenchmarkRunner.addAggregateFailureIfBelowMinimum(failures, aggregate, metric, minimum));
        if (!failures.isEmpty()) {
            throw new IllegalStateException("parser benchmark thresholds failed: " + String.join("; ", failures));
        }
    }

    private static void requireAggregateMaximums(Map<String, Double> aggregate, Map<String, Double> maximums) {
        var failures = new ArrayList<String>();
        maximums.forEach((metric, maximum) ->
                ParserBenchmarkRunner.addAggregateFailureIfAboveMaximum(failures, aggregate, metric, maximum));
        if (!failures.isEmpty()) {
            throw new IllegalStateException("parser benchmark thresholds failed: " + String.join("; ", failures));
        }
    }

    private static Map<String, Double> selectAggregateThresholds(
            Map<String, Double> thresholds, Map<String, Double> aggregate) {
        var selected = new LinkedHashMap<String, Double>();
        thresholds.forEach((metric, value) -> {
            if (aggregate.containsKey(metric)) {
                selected.put(metric, value);
            }
        });
        return selected;
    }

    private static Map<String, Double> withoutKeys(Map<String, Double> thresholds, Map<String, Double> removed) {
        var remaining = new LinkedHashMap<String, Double>();
        thresholds.forEach((metric, value) -> {
            if (!removed.containsKey(metric)) {
                remaining.put(metric, value);
            }
        });
        return remaining;
    }

    private static Map<String, Double> mergedMetrics(Map<String, Double> base, Map<String, Double> external) {
        var merged = new LinkedHashMap<String, Double>(base);
        merged.putAll(external);
        return merged;
    }

    private static ExternalMetrics externalMetrics(Path base, JsonNode root) {
        JsonNode node = root.path("externalEvaluations");
        if (node.isMissingNode() || node.isNull()) {
            return new ExternalMetrics(Map.of(), Map.of(), Map.of());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("externalEvaluations must be an object");
        }
        var metrics = new LinkedHashMap<String, Map<String, Object>>();
        var values = new LinkedHashMap<String, Double>();
        var evaluations = new LinkedHashMap<String, String>();
        node.properties().forEach(entry -> {
            String name = entry.getKey();
            if (!"opendataloader".equals(name)) {
                throw new IllegalArgumentException("unsupported external evaluation: " + name);
            }
            evaluations.put(name, entry.getValue().asText());
            Path path = base.resolve(entry.getValue().asText()).normalize();
            var imported = openDataLoaderMetrics(path);
            metrics.put(name, imported.metrics());
            values.putAll(imported.values());
        });
        return new ExternalMetrics(evaluations, metrics, values);
    }

    private static ExternalMetricSet openDataLoaderMetrics(Path path) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(path));
            var metrics = new LinkedHashMap<String, Object>();
            var values = new LinkedHashMap<String, Double>();
            putMetric(metrics, values, "nid", "opendataloader_nid", root.path("metrics").path("score").path("nid_mean"));
            putMetric(
                    metrics, values, "teds", "opendataloader_teds", root.path("metrics").path("score").path("teds_mean"));
            putMetric(metrics, values, "mhs", "opendataloader_mhs", root.path("metrics").path("score").path("mhs_mean"));
            putMetric(metrics, values, "speed", "opendataloader_speed", openDataLoaderSpeed(root));
            metrics.put("evaluationSha256", sha256(path));
            return new ExternalMetricSet(metrics, values);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid OpenDataLoader evaluation: " + path, e);
        }
    }

    private static JsonNode openDataLoaderSpeed(JsonNode root) {
        JsonNode speed = root.path("speed").path("elapsed_per_doc");
        return speed.isNumber() ? speed : root.path("summary").path("elapsed_per_doc");
    }

    private static void putMetric(
            Map<String, Object> metrics, Map<String, Double> values, String field, String key, JsonNode node) {
        if (!node.isNumber()) {
            return;
        }
        double value = node.asDouble();
        metrics.put(field, value);
        values.put(key, value);
    }

    private static List<ParserBenchmarkCase> cases(Path base, JsonNode nodes, boolean offline) {
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalArgumentException("parser benchmark corpus requires at least one case");
        }
        var loaded = new ArrayList<ParserBenchmarkCase>();
        nodes.forEach(node -> loaded.add(benchmarkCase(base, node, offline)));
        return loaded;
    }

    private static ParserBenchmarkCase benchmarkCase(Path base, JsonNode node, boolean offline) {
        String name = text(node, "name");
        Path source = source(base, node, name, offline);
        Path expectedMarkdown = existing(base, node, "expectedMarkdown", name);
        requireField(node, "expectedDocument", name);
        Path expectedDocument = existing(base, node, "expectedDocument", name);
        try {
            return ParserBenchmarkCase.fromPdf(
                    name,
                    optionalText(node, "labelId"),
                    tags(node),
                    optionalText(node, "sourceSha256"),
                    optionalValues(node, "fixtureTypes"),
                    optionalValues(node, "behaviors"),
                    source,
                    Files.readString(expectedMarkdown),
                    preset(node),
                    TrustDocumentJson.fromJsonFull(Files.readString(expectedDocument)));
        } catch (IOException | ParseException e) {
            throw new IllegalArgumentException("invalid parser benchmark case '" + name + "'", e);
        }
    }

    private static Labeling labeling(
            JsonNode root,
            Optional<String> qualityProfile,
            JsonNode caseNodes,
            Map<String, Double> minimums,
            Map<String, Double> maximums) {
        String kind = optionalText(root, "kind").orElse("generated");
        if (!kind.equals("human-labeled")) {
            return new Labeling(
                    kind,
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Optional.empty());
        }
        JsonNode node = root.path("labeling");
        String version = requiredNestedText(node, "labelSetVersion", kind);
        requiredNestedText(node, "reviewedAt", kind);
        requiredNestedText(node, "reviewer", kind);
        Optional<String> reviewType = reviewType(node, qualityProfile);
        List<String> metrics = requiredMetrics(node, kind);
        for (String metric : metrics) {
            if (!minimums.containsKey(metric) && !maximums.containsKey(metric)) {
                throw new IllegalArgumentException(
                        "human-labeled corpus required metric missing from minimums or maximums: " + metric);
            }
        }
        requireCaseLabels(caseNodes, qualityProfile);
        var requiredTags = requiredTags(node, qualityProfile);
        var coverage = minCasesPerTag(requiredTags, node, qualityProfile);
        requireCoverage(caseNodes, requiredTags, coverage, qualityProfile);
        var requiredFixtureTypes = optionalValues(node, "requiredFixtureTypes");
        var fixtureCoverage = minCasesPerField(requiredFixtureTypes, node, "minCasesPerFixtureType");
        requireFieldCoverage(caseNodes, "fixtureTypes", requiredFixtureTypes, fixtureCoverage);
        var requiredBehaviors = optionalValues(node, "requiredBehaviors");
        var behaviorCoverage = minCasesPerField(requiredBehaviors, node, "minCasesPerBehavior");
        requireFieldCoverage(caseNodes, "behaviors", requiredBehaviors, behaviorCoverage);
        var totalCases = minTotalCases(node, caseNodes, qualityProfile, reviewType);
        requireSourceHashes(caseNodes, qualityProfile, reviewType);
        requireCoreParserAccuracyMetrics(metrics, qualityProfile, reviewType);
        requireCoreParserAccuracyTags(requiredTags, qualityProfile, reviewType);
        return new Labeling(
                kind,
                Optional.of(version),
                reviewType,
                metrics,
                requiredTags,
                coverage,
                requiredFixtureTypes,
                fixtureCoverage,
                requiredBehaviors,
                behaviorCoverage,
                totalCases);
    }

    private static void requireCoreParserAccuracyTags(
            List<String> tags, Optional<String> qualityProfile, Optional<String> reviewType) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()
                || reviewType.filter("human-reviewed"::equals).isEmpty()) {
            return;
        }
        var missing = HUMAN_REVIEWED_PARSER_ACCURACY_TAGS.stream()
                .filter(tag -> !tags.contains(tag))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-reviewed corpus requiredTags missing: "
                            + String.join(", ", missing));
        }
    }

    private static void requireCoreParserAccuracyMetrics(
            List<String> metrics, Optional<String> qualityProfile, Optional<String> reviewType) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()
                || reviewType.filter("human-reviewed"::equals).isEmpty()) {
            return;
        }
        var missing = HUMAN_REVIEWED_PARSER_ACCURACY_METRICS.stream()
                .filter(metric -> !metrics.contains(metric))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-reviewed corpus requiredMetrics missing: "
                            + String.join(", ", missing));
        }
    }

    private static void requireSourceHashes(
            JsonNode caseNodes, Optional<String> qualityProfile, Optional<String> reviewType) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()
                || reviewType.filter("human-reviewed"::equals).isEmpty()
                || !caseNodes.isArray()) {
            return;
        }
        caseNodes.forEach(node -> {
            String name = node.path("name").asText("<unnamed>");
            if (node.path("sourceSha256").asText().isBlank()) {
                throw new IllegalArgumentException(
                        "parser-accuracy human-reviewed corpus case '" + name + "' requires sourceSha256");
            }
        });
    }

    private static Optional<Integer> minTotalCases(
            JsonNode labeling, JsonNode caseNodes, Optional<String> qualityProfile, Optional<String> reviewType) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()) {
            return Optional.empty();
        }
        if (reviewType.filter("human-reviewed"::equals).isEmpty()) {
            return optionalPositiveInteger(labeling, "minTotalCases");
        }
        int minimum = labeling.path("minTotalCases").asInt(0);
        if (minimum < 1) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-reviewed corpus requires labeling.minTotalCases >= 1");
        }
        int actual = caseNodes.isArray() ? caseNodes.size() : 0;
        if (actual < minimum) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-reviewed corpus minTotalCases failed: minimum="
                            + minimum
                            + " actual="
                            + actual);
        }
        return Optional.of(minimum);
    }

    private static Optional<Integer> optionalPositiveInteger(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return Optional.empty();
        }
        int value = node.path(field).asInt(0);
        if (value < 1) {
            throw new IllegalArgumentException(
                    "parser-accuracy corpus labeling." + field + " must be >= 1 when present");
        }
        return Optional.of(value);
    }

    private static Optional<String> reviewType(JsonNode labeling, Optional<String> qualityProfile) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()) {
            return optionalText(labeling, "reviewType");
        }
        String type = labeling.path("reviewType").asText();
        if (type.isBlank()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus requires labeling.reviewType");
        }
        if (!type.equals("human-reviewed") && !type.equals("generated-seed")) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus reviewType must be human-reviewed or generated-seed");
        }
        return Optional.of(type);
    }

    private static void requireCaseLabels(JsonNode caseNodes, Optional<String> qualityProfile) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent() || !caseNodes.isArray()) {
            return;
        }
        caseNodes.forEach(node -> {
            String name = node.path("name").asText("<unnamed>");
            if (node.path("labelId").asText().isBlank()) {
                throw new IllegalArgumentException(
                        "parser-accuracy human-labeled corpus case '" + name + "' requires labelId");
            }
            if (!node.path("tags").isArray() || node.path("tags").isEmpty()) {
                throw new IllegalArgumentException(
                        "parser-accuracy human-labeled corpus case '" + name + "' requires tags");
            }
        });
    }

    private static List<String> requiredTags(JsonNode labeling, Optional<String> qualityProfile) {
        if (!qualityProfile.filter("parser-accuracy"::equals).isPresent()) {
            return List.of();
        }
        JsonNode tags = labeling.path("requiredTags");
        if (!tags.isArray() || tags.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus requires labeling.requiredTags");
        }
        var values = new ArrayList<String>();
        tags.forEach(tag -> {
            String value = tag.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus requires nonblank labeling.requiredTags");
        }
        return values;
    }

    private static Map<String, Integer> minCasesPerTag(
            List<String> requiredTags, JsonNode labeling, Optional<String> qualityProfile) {
        if (requiredTags.isEmpty()) {
            return Map.of();
        }
        int minimum = labeling.path("minCasesPerTag").asInt(0);
        if (minimum < 1) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus requires labeling.minCasesPerTag >= 1");
        }
        var coverage = new LinkedHashMap<String, Integer>();
        requiredTags.forEach(tag -> coverage.put(tag, minimum));
        return coverage;
    }

    private static void requireCoverage(
            JsonNode caseNodes,
            List<String> requiredTags,
            Map<String, Integer> minimums,
            Optional<String> qualityProfile) {
        if (requiredTags.isEmpty() || !qualityProfile.filter("parser-accuracy"::equals).isPresent()) {
            return;
        }
        var counts = tagCounts(caseNodes);
        var failures = new ArrayList<String>();
        requiredTags.forEach(tag -> {
            int actual = counts.getOrDefault(tag, 0);
            int minimum = minimums.getOrDefault(tag, 1);
            if (actual < minimum) {
                failures.add(tag + " minimum=" + minimum + " actual=" + actual);
            }
        });
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus coverage failed: " + String.join("; ", failures));
        }
    }

    private static Map<String, Integer> tagCounts(JsonNode caseNodes) {
        return fieldCounts(caseNodes, "tags");
    }

    private static List<String> optionalValues(JsonNode node, String field) {
        if (!node.path(field).isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        node.path(field).forEach(item -> {
            String value = item.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private static Map<String, Integer> minCasesPerField(List<String> required, JsonNode labeling, String field) {
        if (required.isEmpty()) {
            return Map.of();
        }
        int minimum = labeling.path(field).asInt(0);
        if (minimum < 1) {
            throw new IllegalArgumentException("parser-accuracy human-labeled corpus requires labeling." + field + " >= 1");
        }
        var coverage = new LinkedHashMap<String, Integer>();
        required.forEach(value -> coverage.put(value, minimum));
        return coverage;
    }

    private static void requireFieldCoverage(
            JsonNode caseNodes, String field, List<String> required, Map<String, Integer> minimums) {
        if (required.isEmpty()) {
            return;
        }
        var counts = fieldCounts(caseNodes, field);
        var failures = new ArrayList<String>();
        required.forEach(value -> {
            int actual = counts.getOrDefault(value, 0);
            int minimum = minimums.getOrDefault(value, 1);
            if (actual < minimum) {
                failures.add(value + " minimum=" + minimum + " actual=" + actual);
            }
        });
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "parser-accuracy human-labeled corpus " + field + " coverage failed: " + String.join("; ", failures));
        }
    }

    private static Map<String, Integer> fieldCounts(JsonNode caseNodes, String field) {
        var counts = new LinkedHashMap<String, Integer>();
        if (!caseNodes.isArray()) {
            return counts;
        }
        caseNodes.forEach(node -> node.path(field).forEach(tag -> {
            String value = tag.asText();
            if (!value.isBlank()) {
                counts.merge(value, 1, Integer::sum);
            }
        }));
        return counts;
    }

    private static List<String> requiredMetrics(JsonNode labeling, String kind) {
        JsonNode metrics = labeling.path("requiredMetrics");
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw new IllegalArgumentException(kind + " corpus requires labeling.requiredMetrics");
        }
        var values = new ArrayList<String>();
        metrics.forEach(metric -> {
            String value = metric.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        if (values.isEmpty()) {
            throw new IllegalArgumentException(kind + " corpus requires nonblank labeling.requiredMetrics");
        }
        return values;
    }

    private static String requiredNestedText(JsonNode node, String field, String kind) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(kind + " corpus requires labeling." + field);
        }
        return value;
    }

    private static Path source(Path base, JsonNode node, String name, boolean offline) {
        if (node.hasNonNull("sourceUrl") && !node.path("sourceUrl").asText().isBlank()) {
            return remoteSource(base, node, name, offline);
        }
        Path path = existing(base, node, "source", name);
        optionalText(node, "sourceSha256").ifPresent(expected -> {
            try {
                requireSha(path, expected, name);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "parser benchmark case '" + name + "' failed to verify sourceSha256", e);
            }
        });
        return path;
    }

    private static Path remoteSource(Path base, JsonNode node, String name, boolean offline) {
        String sourceUrl = text(node, "sourceUrl");
        String expectedSha = text(node, "sourceSha256");
        try {
            var cache = base.resolve(".doctruth-corpus-cache");
            Files.createDirectories(cache);
            var target = cache.resolve(name + "-" + expectedSha.replace("sha256:", "") + ".pdf");
            if (!Files.exists(target)) {
                if (offline) {
                    throw new IllegalArgumentException(
                            "parser benchmark case '" + name
                                    + "' offline mode refuses remote benchmark source: " + sourceUrl);
                }
                var request = HttpRequest.newBuilder(URI.create(sourceUrl)).GET().build();
                var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("HTTP " + response.statusCode());
                }
                Files.write(target, response.body());
            }
            requireSha(target, expectedSha, name);
            return target;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalArgumentException("parser benchmark case '" + name + "' failed to download sourceUrl", e);
        }
    }

    private static ParserPreset preset(JsonNode node) {
        if (!node.hasNonNull("preset") || node.path("preset").asText().isBlank()) {
            return ParserPreset.LITE;
        }
        return ParserPreset.fromId(node.path("preset").asText());
    }

    private static List<String> tags(JsonNode node) {
        var values = new ArrayList<String>();
        node.path("tags").forEach(tag -> {
            String value = tag.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private static Path existing(Path base, JsonNode node, String field, String caseName) {
        String value = text(node, field);
        Path path = base.resolve(value).normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(
                    "parser benchmark case '" + caseName + "' missing " + field + ": " + value);
        }
        return path;
    }

    private static void requireField(JsonNode node, String field, String caseName) {
        if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) {
            throw new IllegalArgumentException(
                    "parser benchmark case '" + caseName + "' missing required field: " + field);
        }
    }

    private static void requireSha(Path path, String expected, String caseName) throws IOException {
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException(
                    "parser benchmark case '" + caseName + "' SHA-256 mismatch: actual=" + actual);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            var builder = new StringBuilder("sha256:");
            for (byte b : hash) {
                builder.append("%02x".formatted(b));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, Double> thresholds(JsonNode root, String field) {
        var thresholds = new LinkedHashMap<String, Double>();
        root.path(field).fields().forEachRemaining(entry -> thresholds.put(entry.getKey(), entry.getValue().asDouble()));
        return thresholds;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing or blank field: " + field);
        }
        return value;
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return Optional.empty();
        }
        String value = node.path(field).asText();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private record Labeling(
            String kind,
            Optional<String> labelSetVersion,
            Optional<String> reviewType,
            List<String> requiredMetrics,
            List<String> requiredTags,
            Map<String, Integer> minCasesPerTag,
            List<String> requiredFixtureTypes,
            Map<String, Integer> minCasesPerFixtureType,
            List<String> requiredBehaviors,
            Map<String, Integer> minCasesPerBehavior,
            Optional<Integer> minTotalCases) {}

    private record ExternalMetrics(
            Map<String, String> evaluations, Map<String, Map<String, Object>> metrics, Map<String, Double> values) {}

    private record ExternalMetricSet(Map<String, Object> metrics, Map<String, Double> values) {}
}
