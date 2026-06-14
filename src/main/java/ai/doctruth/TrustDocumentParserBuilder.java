package ai.doctruth;

import java.nio.file.Path;
import java.util.Objects;

import ai.doctruth.internal.runtime.DocTruthRuntime;

/**
 * Parser builder for the document-first SDK path.
 *
 * @since 1.0.0
 */
public final class TrustDocumentParserBuilder {

    private final ParsedDocument document;
    private final Path sourcePath;
    private final ParserPreset preset;
    private final ParserBackendMode backend;
    private final Path runtime;

    TrustDocumentParserBuilder(ParsedDocument document, ParserPreset preset) {
        this.document = Objects.requireNonNull(document, "document");
        this.sourcePath = null;
        this.preset = Objects.requireNonNull(preset, "preset");
        this.backend = ParserBackendMode.PDFBOX;
        this.runtime = null;
    }

    TrustDocumentParserBuilder(Path sourcePath, ParserPreset preset) {
        this(sourcePath, preset, ParserBackendMode.AUTO, null);
    }

    private TrustDocumentParserBuilder(Path sourcePath, ParserPreset preset, ParserBackendMode backend, Path runtime) {
        this.document = null;
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.preset = Objects.requireNonNull(preset, "preset");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.runtime = runtime;
    }

    public TrustDocumentParserBuilder withParser(ParserPreset preset) {
        if (sourcePath == null) {
            return new TrustDocumentParserBuilder(document, preset);
        }
        return new TrustDocumentParserBuilder(sourcePath, preset, backend, runtime);
    }

    public TrustDocumentParserBuilder backend(ParserBackendMode backend) {
        if (sourcePath == null) {
            if (backend != ParserBackendMode.PDFBOX) {
                throw new IllegalStateException("parsed-document parser path only supports PDFBox fallback mode");
            }
            return this;
        }
        return new TrustDocumentParserBuilder(sourcePath, preset, backend, runtime);
    }

    public TrustDocumentParserBuilder runtime(Path runtime) {
        if (sourcePath == null) {
            throw new IllegalStateException("parsed-document parser path cannot use a runtime sidecar");
        }
        return new TrustDocumentParserBuilder(sourcePath, preset, backend, Objects.requireNonNull(runtime, "runtime"));
    }

    public TrustDocument parse() throws ParseException {
        if (sourcePath == null) {
            return TrustDocument.fromParsed(document, document.docId(), preset.parserRun()).withEvaluatedAuditGrade();
        }
        return switch (backend) {
            case AUTO -> new SidecarParserBackend(requiredRuntime()).parse(request("sidecar")).withEvaluatedAuditGrade();
            case PDFBOX -> new PdfBoxParserBackend().parse(request("pdfbox")).withEvaluatedAuditGrade();
            case SIDECAR -> new SidecarParserBackend(requiredRuntime()).parse(request("sidecar")).withEvaluatedAuditGrade();
        };
    }

    private ParserRequest request(String backendName) throws ParseException {
        return new ParserRequest(
                sourcePath,
                TrustDocumentParser.sha256SourceFile(sourcePath),
                preset.parserRun(backendName),
                preset.runtimePolicy().offlineMode(),
                preset.runtimePolicy().allowModelDownloads());
    }

    private Path requiredRuntime() throws ParseException {
        return java.util.Optional.ofNullable(runtime)
                .or(DocTruthRuntime::configuredCommand)
                .orElseThrow(() -> new ParseException(
                        "RUST_RUNTIME_NOT_CONFIGURED",
                        "Rust runtime is required unless ParserBackendMode.PDFBOX is selected explicitly",
                        sourcePath.toString(),
                        java.util.OptionalInt.empty()));
    }
}
