package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.ParserBenchmarkCorpus;
import ai.doctruth.ParserBenchmarkResult;
import ai.doctruth.ParserBenchmarkRunner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class BenchmarkCorpusCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    BenchmarkCorpusCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var corpus = load(options.manifest(), options.offline());
        var results = corpus.evaluate();
        try {
            corpus.requireThresholds();
        } catch (IllegalStateException e) {
            throw new CliException(e.getMessage(), e);
        }
        writeReport(options, corpus, results);
        if (options.json()) {
            context.out().println(json(corpus, results, true));
        } else {
            context.out().print(text(corpus, results));
        }
    }

    private static ParserBenchmarkCorpus load(Path manifest, boolean offline) throws CliException {
        try {
            return ParserBenchmarkCorpus.load(manifest, offline);
        } catch (IllegalArgumentException e) {
            throw new CliException("failed to load benchmark corpus: " + e.getMessage(), e);
        }
    }

    private static String text(ParserBenchmarkCorpus corpus, List<ParserBenchmarkResult> results) {
        var out = new StringBuilder();
        out.append("corpus: ").append(corpus.name()).append('\n');
        appendLabeling(out, corpus);
        out.append("cases: ").append(results.size()).append('\n');
        out.append("metrics:\n");
        ParserBenchmarkRunner.aggregateMetrics(results).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append("%.3f".formatted(entry.getValue()))
                        .append('\n'));
        for (var result : results) {
            out.append("- ").append(result.name()).append('\n');
            result.labelId().ifPresent(labelId -> out.append("  labelId: ").append(labelId).append('\n'));
            if (!result.tags().isEmpty()) {
                out.append("  tags: ").append(String.join(", ", result.tags())).append('\n');
            }
            result.metrics().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append("  ")
                            .append(entry.getKey())
                            .append(": ")
                            .append("%.3f".formatted(entry.getValue()))
                            .append('\n'));
        }
        out.append("thresholds: passed\n");
        return out.toString();
    }

    private static void appendLabeling(StringBuilder out, ParserBenchmarkCorpus corpus) {
        out.append("kind: ").append(corpus.kind()).append('\n');
        corpus.qualityProfile().ifPresent(profile -> out.append("qualityProfile: ").append(profile).append('\n'));
        corpus.reviewType().ifPresent(type -> out.append("reviewType: ").append(type).append('\n'));
        corpus.labelSetVersion().ifPresent(version -> out.append("labelSetVersion: ").append(version).append('\n'));
        if (!corpus.requiredMetrics().isEmpty()) {
            out.append("requiredMetrics: ").append(String.join(", ", corpus.requiredMetrics())).append('\n');
        }
        if (!corpus.requiredTags().isEmpty()) {
            out.append("requiredTags: ").append(String.join(", ", corpus.requiredTags())).append('\n');
        }
        if (!corpus.minCasesPerTag().isEmpty()) {
            out.append("minCasesPerTag: ");
            out.append(joinEntries(corpus.minCasesPerTag())).append('\n');
        }
        corpus.minTotalCases().ifPresent(value -> out.append("minTotalCases: ").append(value).append('\n'));
    }

    private static String joinEntries(Map<String, Integer> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String json(ParserBenchmarkCorpus corpus, List<ParserBenchmarkResult> results, boolean passed)
            throws CliException {
        var root = new LinkedHashMap<String, Object>();
        populateReport(root, corpus, results, passed);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CliException("failed to render benchmark corpus JSON: " + e.getMessage(), e);
        }
    }

    private static void writeReport(
            Options options, ParserBenchmarkCorpus corpus, List<ParserBenchmarkResult> results) throws CliException {
        if (options.reportOut().isEmpty()) {
            return;
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("reportFormat", "doctruth.parser-benchmark.report.v1");
        root.put("manifest", options.manifest().toAbsolutePath().normalize().toString());
        root.put("manifestSha256", sha256(options.manifest()));
        populateReport(root, corpus, results, true);
        try {
            Path report = options.reportOut().get();
            Path parent = report.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), root);
        } catch (IOException e) {
            throw new CliException("failed to write benchmark corpus report: " + e.getMessage(), e);
        }
    }

    private static String sha256(Path path) throws CliException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            var builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append("%02x".formatted(value));
            }
            return builder.toString();
        } catch (IOException e) {
            throw new CliException("failed to hash benchmark corpus manifest: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new CliException("SHA-256 is unavailable", e);
        }
    }

    private static void populateReport(
            Map<String, Object> root, ParserBenchmarkCorpus corpus, List<ParserBenchmarkResult> results, boolean passed) {
        root.put("corpus", corpus.name());
        root.put("kind", corpus.kind());
        corpus.labelSetVersion().ifPresent(version -> root.put("labelSetVersion", version));
        corpus.reviewType().ifPresent(type -> root.put("reviewType", type));
        corpus.qualityProfile().ifPresent(profile -> root.put("qualityProfile", profile));
        root.put("requiredMetrics", corpus.requiredMetrics());
        root.put("requiredTags", corpus.requiredTags());
        root.put("minCasesPerTag", corpus.minCasesPerTag());
        corpus.minTotalCases().ifPresent(value -> root.put("minTotalCases", value));
        root.put("caseCount", results.size());
        root.put("casesPerTag", casesPerTag(results));
        root.put("coverageRequired", corpus.minCasesPerTag());
        root.put("coverageSatisfied", coverageSatisfied(corpus.minCasesPerTag(), results));
        root.put("validityInputs", validityInputs());
        root.put("minimums", corpus.minimums());
        root.put("maximums", corpus.maximums());
        root.put("passed", passed);
        root.put("metrics", ParserBenchmarkRunner.aggregateMetrics(results));
        root.put("cases", results.stream().map(BenchmarkCorpusCommand::caseNode).toList());
    }

    private static Map<String, Integer> casesPerTag(List<ParserBenchmarkResult> results) {
        var counts = new LinkedHashMap<String, Integer>();
        results.stream()
                .flatMap(result -> result.tags().stream())
                .sorted()
                .forEach(tag -> counts.merge(tag, 1, Integer::sum));
        return counts;
    }

    private static Map<String, Boolean> coverageSatisfied(
            Map<String, Integer> minimums, List<ParserBenchmarkResult> results) {
        var actual = casesPerTag(results);
        var satisfied = new LinkedHashMap<String, Boolean>();
        minimums.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> satisfied.put(
                        entry.getKey(), actual.getOrDefault(entry.getKey(), 0) >= entry.getValue()));
        return satisfied;
    }

    private static Map<String, Object> validityInputs() {
        var inputs = new LinkedHashMap<String, Object>();
        inputs.put("sourceHashes", true);
        inputs.put("manifestHash", true);
        inputs.put("parserConfig", "TrustDocument");
        inputs.put("modelCacheManifest", "not-required");
        inputs.put("thresholds", true);
        inputs.put("expectedLabels", true);
        inputs.put("actualTrustDocument", true);
        return inputs;
    }

    private static Map<String, Object> caseNode(ParserBenchmarkResult result) {
        var node = new LinkedHashMap<String, Object>();
        node.put("name", result.name());
        result.labelId().ifPresent(labelId -> node.put("labelId", labelId));
        result.sourceSha256().ifPresent(sourceSha256 -> node.put("sourceSha256", sourceSha256));
        node.put("tags", result.tags());
        node.put("metrics", result.metrics());
        node.put("replay", replayNode(result));
        return node;
    }

    private static Map<String, Boolean> replayNode(ParserBenchmarkResult result) {
        var replay = new LinkedHashMap<String, Boolean>();
        replay.put("sourceRefReplayable", result.sourceSha256().isPresent());
        replay.put("quoteReplayable", result.metric("quote_anchor_accuracy") >= 1.0);
        replay.put("evidenceSpanReplayable", result.metric("evidence_span_accuracy") >= 1.0);
        return replay;
    }

    private record Options(Path manifest, boolean json, boolean offline, Optional<Path> reportOut) {
        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException(
                        "usage: doctruth benchmark-corpus <manifest.json> [--json] [--offline] [--report-out <report.json>]");
            }
            Path manifest = Path.of(args[1]);
            boolean json = false;
            boolean offline = false;
            Optional<Path> reportOut = Optional.empty();
            var tail = Arrays.copyOfRange(args, 2, args.length);
            for (int index = 0; index < tail.length; index++) {
                String arg = tail[index];
                switch (arg) {
                    case "--json" -> json = true;
                    case "--offline" -> offline = true;
                    case "--report-out" -> {
                        if (index + 1 >= tail.length) {
                            throw new UsageException("--report-out requires a path");
                        }
                        reportOut = Optional.of(Path.of(tail[++index]));
                    }
                    default -> throw new UsageException("unknown benchmark-corpus option: " + arg);
                }
            }
            return new Options(manifest, json, offline, reportOut);
        }
    }
}
