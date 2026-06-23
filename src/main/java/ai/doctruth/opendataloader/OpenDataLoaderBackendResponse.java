package ai.doctruth.opendataloader;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import ai.doctruth.ParserWarning;
import ai.doctruth.TrustDocument;

/** Immutable response from the OpenDataLoader-compatible Java parser backend. */
public final class OpenDataLoaderBackendResponse {

    private final String backend;
    private final String schemaVersion;
    private final String markdown;
    private final List<OpenDataLoaderBlock> blocks;
    private final List<OpenDataLoaderTable> tables;
    private final List<OpenDataLoaderBlock> headings;
    private final List<OpenDataLoaderSourceRef> sourceMap;
    private final List<ParserWarning> warnings;
    private final Map<String, Long> metrics;
    private final TrustDocument trustDocument;

    private OpenDataLoaderBackendResponse(
            String backend,
            String schemaVersion,
            String markdown,
            List<OpenDataLoaderBlock> blocks,
            List<OpenDataLoaderTable> tables,
            List<OpenDataLoaderBlock> headings,
            List<OpenDataLoaderSourceRef> sourceMap,
            List<ParserWarning> warnings,
            Map<String, Long> metrics,
            TrustDocument trustDocument) {
        this.backend = requireText(backend, "backend");
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
        this.markdown = Objects.requireNonNull(markdown, "markdown");
        this.blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        this.tables = List.copyOf(Objects.requireNonNull(tables, "tables"));
        this.headings = List.copyOf(Objects.requireNonNull(headings, "headings"));
        this.sourceMap = List.copyOf(Objects.requireNonNull(sourceMap, "sourceMap"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        this.metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
        this.trustDocument = Objects.requireNonNull(trustDocument, "trustDocument");
    }

    public static OpenDataLoaderBackendResponse fromParts(
            String backend,
            String schemaVersion,
            String markdown,
            List<OpenDataLoaderBlock> blocks,
            List<OpenDataLoaderTable> tables,
            List<OpenDataLoaderBlock> headings,
            List<OpenDataLoaderSourceRef> sourceMap,
            List<ParserWarning> warnings,
            Map<String, Long> metrics,
            TrustDocument trustDocument) {
        return new OpenDataLoaderBackendResponse(
                backend,
                schemaVersion,
                markdown,
                blocks,
                tables,
                headings,
                sourceMap,
                warnings,
                metrics,
                trustDocument);
    }

    public String backend() {
        return backend;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String markdown() {
        return markdown;
    }

    public List<OpenDataLoaderBlock> blocks() {
        return blocks;
    }

    public List<OpenDataLoaderTable> tables() {
        return tables;
    }

    public List<OpenDataLoaderBlock> headings() {
        return headings;
    }

    public List<OpenDataLoaderSourceRef> sourceMap() {
        return sourceMap;
    }

    public List<ParserWarning> warnings() {
        return warnings;
    }

    public Map<String, Long> metrics() {
        return metrics;
    }

    public TrustDocument trustDocument() {
        return trustDocument;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
