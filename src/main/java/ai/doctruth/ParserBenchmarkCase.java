package ai.doctruth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One parser benchmark fixture after parsing.
 *
 * @since 1.0.0
 */
public record ParserBenchmarkCase(
        String name,
        ParserBenchmarkLabel label,
        TrustDocument document,
        ParserBenchmarkExpectation expectation,
        ParserBenchmarkResources resources) {

    public ParserBenchmarkCase(String name, TrustDocument document, String expectedMarkdown) {
        this(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, Optional.empty()),
                ParserBenchmarkResources.ZERO);
    }

    public ParserBenchmarkCase(
            String name, TrustDocument document, String expectedMarkdown, Optional<TrustDocument> expectedDocument) {
        this(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, expectedDocument),
                ParserBenchmarkResources.ZERO);
    }

    public ParserBenchmarkCase(
            String name,
            TrustDocument document,
            String expectedMarkdown,
            Optional<TrustDocument> expectedDocument,
            ParserBenchmarkResources resources) {
        this(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, expectedDocument),
                resources);
    }

    public ParserBenchmarkCase(
            String name,
            TrustDocument document,
            String expectedMarkdown,
            Optional<TrustDocument> expectedDocument,
            double parserLatencyMs) {
        this(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, expectedDocument),
                new ParserBenchmarkResources(parserLatencyMs, 0.0, 0.0));
    }

    public ParserBenchmarkCase(
            String name,
            TrustDocument document,
            String expectedMarkdown,
            Optional<TrustDocument> expectedDocument,
            double parserLatencyMs,
            double rssPeakMb,
            double modelCacheSizeMb) {
        this(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, expectedDocument),
                new ParserBenchmarkResources(parserLatencyMs, rssPeakMb, modelCacheSizeMb));
    }

    public static ParserBenchmarkCase fromPdf(String name, Path sourcePath, String expectedMarkdown)
            throws ParseException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        long start = System.nanoTime();
        var document = TrustDocumentParser.parse(sourcePath);
        return new ParserBenchmarkCase(
                name,
                ParserBenchmarkLabel.NONE,
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, Optional.empty()),
                resourceMetrics(start));
    }

    public static ParserBenchmarkCase fromPdf(
            String name, Path sourcePath, String expectedMarkdown, TrustDocument expectedDocument) throws ParseException {
        return fromPdf(name, sourcePath, expectedMarkdown, ParserPreset.LITE, expectedDocument);
    }

    public static ParserBenchmarkCase fromPdf(
            String name,
            Path sourcePath,
            String expectedMarkdown,
            ParserPreset preset,
            TrustDocument expectedDocument)
            throws ParseException {
        return fromPdf(name, Optional.empty(), List.of(), sourcePath, expectedMarkdown, preset, expectedDocument);
    }

    public static ParserBenchmarkCase fromPdf(
            String name,
            Optional<String> labelId,
            List<String> tags,
            Path sourcePath,
            String expectedMarkdown,
            ParserPreset preset,
            TrustDocument expectedDocument)
            throws ParseException {
        return fromPdf(
                name,
                labelId,
                tags,
                Optional.empty(),
                sourcePath,
                expectedMarkdown,
                preset,
                expectedDocument);
    }

    public static ParserBenchmarkCase fromPdf(
            String name,
            Optional<String> labelId,
            List<String> tags,
            Optional<String> sourceSha256,
            Path sourcePath,
            String expectedMarkdown,
            ParserPreset preset,
            TrustDocument expectedDocument)
            throws ParseException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(labelId, "labelId");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(expectedDocument, "expectedDocument");
        long start = System.nanoTime();
        var document = TrustDocumentParser.parse(sourcePath, preset);
        return new ParserBenchmarkCase(
                name,
                new ParserBenchmarkLabel(labelId, tags, sourceSha256),
                document,
                new ParserBenchmarkExpectation(expectedMarkdown, Optional.of(expectedDocument)),
                resourceMetrics(start));
    }

    public ParserBenchmarkCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(resources, "resources");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public Optional<String> labelId() {
        return label.labelId();
    }

    public List<String> tags() {
        return label.tags();
    }

    public Optional<String> sourceSha256() {
        return label.sourceSha256();
    }

    public String expectedMarkdown() {
        return expectation.markdown();
    }

    public Optional<TrustDocument> expectedDocument() {
        return expectation.document();
    }

    public double parserLatencyMs() {
        return resources.parserLatencyMs();
    }

    public double rssPeakMb() {
        return resources.rssPeakMb();
    }

    public double modelCacheSizeMb() {
        return resources.modelCacheSizeMb();
    }

    private static ParserBenchmarkResources resourceMetrics(long startNanos) {
        return new ParserBenchmarkResources(elapsedMs(startNanos), currentMemoryMb(), modelCacheMb());
    }

    private static double elapsedMs(long startNanos) {
        return Math.max(0.0, (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private static double currentMemoryMb() {
        var runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return bytesToMb(Math.max(0, used));
    }

    private static double modelCacheMb() {
        Path cache = modelCacheDirectory();
        if (!Files.exists(cache)) {
            return 0.0;
        }
        try (Stream<Path> paths = Files.walk(cache)) {
            long bytes = paths.filter(Files::isRegularFile).mapToLong(ParserBenchmarkCase::size).sum();
            return bytesToMb(bytes);
        } catch (IOException e) {
            return 0.0;
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Path modelCacheDirectory() {
        return setting("doctruth.model.cache")
                .or(() -> environment("DOCTRUTH_MODEL_CACHE"))
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".cache", "doctruth", "models"));
    }

    private static Optional<String> setting(String key) {
        return Optional.ofNullable(System.getProperty(key)).filter(value -> !value.isBlank());
    }

    private static Optional<String> environment(String key) {
        return Optional.ofNullable(System.getenv(key)).filter(value -> !value.isBlank());
    }

    private static double bytesToMb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
