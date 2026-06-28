package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import ai.doctruth.ParserPreset;
import ai.doctruth.ParserRequest;
import ai.doctruth.PdfBoxParserBackend;
import ai.doctruth.SidecarParserBackend;
import ai.doctruth.TrustDocument;
import ai.doctruth.internal.runtime.DocTruthRuntime;

final class ParseCommand {

    private final CliContext context;

    ParseCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = ParseOptions.parse(args, context.env());
        if (options.usesTrustDocumentParser()) {
            var trust = options.parseTrustDocument();
            if (options.format() == OutputFormat.SUMMARY) {
                printSummary(options.document(), trust, options);
                return;
            }
            if (options.out() != null) {
                options.writeTrustDocument(trust);
                options.writeSourceMapIfRequested(trust);
            } else if (options.writeTrustDocumentToStdout(trust, context.out())) {
                return;
            } else {
                context.out().print(options.renderTrustDocument(trust));
            }
            return;
        }
        var doc = DocumentParsers.parse(options.document());
        if (options.out() != null) {
            options.writeDocument(doc);
            options.writeSourceMapIfRequested(options.trust(doc));
        }
        if (options.shouldPrintDocument() && options.out() == null) {
            if (options.writeDocumentToStdout(doc, context.out())) {
                return;
            }
            String output = options.renderDocument(doc);
            context.out().print(output);
            return;
        }
        printSummary(options.document(), doc, options);
    }

    private void printSummary(Path source, ai.doctruth.ParsedDocument doc, ParseOptions options) {
        var stats = ParsedDocumentStats.from(doc);
        context.out().println(source);
        context.out().println("pages: " + doc.metadata().pageCount());
        context.out().println("sections: " + stats.sections());
        context.out().println("text: " + stats.text());
        context.out().println("tables: " + stats.tables());
        context.out().println("figures: " + stats.figures());
        context.out().println("bbox coverage: " + stats.textWithBbox() + "/" + stats.text());
        if (options.out() != null) {
            context.out().println("output: " + options.out());
        }
    }

    private void printSummary(Path source, TrustDocument doc, ParseOptions options) {
        context.out().println(source);
        context.out().println("pages: " + doc.source().metadata().pageCount());
        context.out().println("units: " + doc.body().units().size());
        context.out().println("tables: " + doc.body().tables().size());
        context.out().println("parser backend: " + doc.parserRun().backend());
        context.out().println("audit grade: " + doc.auditGradeStatus());
        if (options.out() != null) {
            context.out().println("output: " + options.out());
        }
    }

    private static void write(Path out, String output) throws CliException {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, output);
        } catch (IOException e) {
            throw new CliException("failed to write parsed output: " + e.getMessage(), e);
        }
    }

    private record ParseOptions(
            Path document,
            OutputFormat format,
            OutputProfile profile,
            boolean bboxes,
            boolean sourceMap,
            Path out,
            ParserBackendChoice backend,
            Path runtime,
            ParserPreset preset) {
        static ParseOptions parse(String[] args, Map<String, String> env) {
            if (args.length < 2) {
                throw new UsageException(
                        "usage: doctruth parse <document> [--json|--markdown|--format <format>] [--profile <profile>] [--preset <preset>] [--backend auto|pdfbox|sidecar] [--runtime <path>] [--source-map] [--bboxes] [-o parsed.out]");
            }
            if (args[1].startsWith("-")) {
                throw new UsageException("parse requires <document> before options");
            }
            Path document = Path.of(args[1]);
            OutputFormat format = OutputFormat.SUMMARY;
            OutputProfile profile = OutputProfile.DEFAULT;
            boolean bboxes = false;
            boolean sourceMap = false;
            Path out = null;
            ParserBackendChoice backend = ParserBackendChoice.AUTO;
            Path runtime = null;
            ParserPreset preset = ParserPreset.LITE;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--json" -> format = chooseFormat(format, OutputFormat.TRUST_JSON, arg);
                    case "--markdown", "--md" -> format = chooseFormat(format, OutputFormat.TRUST_MARKDOWN, arg);
                    case "--format" -> format = chooseFormat(format, OutputFormat.from(cursor.next()), arg);
                    case "--profile" -> profile = OutputProfile.from(cursor.next());
                    case "--preset" -> preset = parserPreset(cursor.next());
                    case "--backend" -> backend = ParserBackendChoice.from(cursor.next());
                    case "--runtime" -> runtime = cursor.nextPath(arg);
                    case "--source-map" -> sourceMap = true;
                    case "--bboxes" -> bboxes = true;
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown parse option: " + arg);
                }
            }
            validate(format, profile, sourceMap, out, backend, runtime);
            runtime = runtime == null && backend != ParserBackendChoice.PDFBOX ? defaultRuntime(env) : runtime;
            return new ParseOptions(document, format, profile, bboxes, sourceMap, out, backend, runtime, preset);
        }

        static ParseOptions parse(String[] args) {
            return parse(args, Map.of());
        }

        boolean shouldPrintDocument() {
            return format != OutputFormat.SUMMARY;
        }

        boolean usesTrustDocumentParser() {
            return backend != ParserBackendChoice.PDFBOX
                    && switch (format) {
                        case TRUST_JSON,
                                TRUST_MARKDOWN,
                                TRUST_PLAIN,
                                TRUST_HTML,
                                TRUST_JSONL,
                                TRUST_AUDIT,
                                TRUST_COMPACT,
                                TRUST_CONTENT_BLOCKS,
                                TRUST_PARSE_TRACE,
                                SUMMARY -> true;
                        case LEGACY_JSON, LEGACY_MARKDOWN -> false;
                    };
        }

        String renderDocument(ai.doctruth.ParsedDocument doc) throws CliException {
            return switch (format) {
                case SUMMARY -> ParsedDocumentJson.toJson(doc);
                case LEGACY_JSON -> ParsedDocumentJson.toJson(doc);
                case LEGACY_MARKDOWN -> ParsedDocumentMarkdown.toMarkdown(doc);
                case TRUST_JSON -> json(trust(doc));
                case TRUST_MARKDOWN -> markdown(trust(doc));
                case TRUST_PLAIN -> trust(doc).toPlainText();
                case TRUST_HTML -> trust(doc).toHtmlReview();
                case TRUST_JSONL -> trust(doc).toJsonLines();
                case TRUST_AUDIT -> trust(doc).toAuditJson();
                case TRUST_COMPACT -> trust(doc).toCompactLlm();
                case TRUST_CONTENT_BLOCKS, TRUST_PARSE_TRACE ->
                    throw new UsageException("layered parser output requires TrustDocument parser path");
            };
        }

        String renderTrustDocument(TrustDocument trust) {
            return switch (format) {
                case TRUST_JSON -> json(trust);
                case TRUST_MARKDOWN -> markdown(trust);
                case TRUST_PLAIN -> trust.toPlainText();
                case TRUST_HTML -> trust.toHtmlReview();
                case TRUST_JSONL -> trust.toJsonLines();
                case TRUST_AUDIT -> trust.toAuditJson();
                case TRUST_COMPACT -> trust.toCompactLlm();
                case TRUST_CONTENT_BLOCKS, TRUST_PARSE_TRACE ->
                    throw new UsageException("layered parser output must be written through the streaming writer path");
                case SUMMARY, LEGACY_JSON, LEGACY_MARKDOWN ->
                    throw new UsageException(
                            "sidecar backend requires --format json|markdown|plain|html|jsonl|audit|compact");
            };
        }

        void writeDocument(ai.doctruth.ParsedDocument doc) throws CliException {
            var trust = trust(doc);
            if (writeTrustDocumentWithWriterPath(trust)) {
                return;
            }
            write(out, renderDocument(doc));
        }

        void writeTrustDocument(TrustDocument trust) throws CliException {
            if (writeTrustDocumentWithWriterPath(trust)) {
                return;
            }
            write(out, renderTrustDocument(trust));
        }

        boolean writeDocumentToStdout(ai.doctruth.ParsedDocument doc, java.io.PrintStream out) throws CliException {
            return writeTrustDocumentToStdout(trust(doc), out);
        }

        boolean writeTrustDocumentToStdout(TrustDocument trust, java.io.PrintStream out) throws CliException {
            switch (format) {
                case TRUST_JSON -> {
                    TrustDocumentCliWriters.writeToPrintStream(out, writer -> {
                        switch (profile) {
                            case DEFAULT, FULL -> TrustDocumentCliWriters.writeJsonFull(trust, writer);
                            case EVIDENCE -> TrustDocumentCliWriters.writeJsonEvidence(trust, writer);
                            default ->
                                throw new UsageException(
                                        "parse profile " + profile.flag + " is not valid for json format");
                        }
                    });
                    return true;
                }
                case TRUST_AUDIT -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeAuditJson(trust, writer));
                    return true;
                }
                case TRUST_JSONL -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeJsonLines(trust, writer));
                    return true;
                }
                case TRUST_COMPACT -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeCompactLlm(trust, writer));
                    return true;
                }
                case TRUST_PLAIN -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writePlainText(trust, writer));
                    return true;
                }
                case TRUST_CONTENT_BLOCKS -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeContentBlocks(trust, writer));
                    return true;
                }
                case TRUST_PARSE_TRACE -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeParseTrace(trust, writer));
                    return true;
                }
                case TRUST_HTML -> {
                    TrustDocumentCliWriters.writeToPrintStream(
                            out, writer -> TrustDocumentCliWriters.writeHtmlReview(trust, writer));
                    return true;
                }
                case TRUST_MARKDOWN -> {
                    TrustDocumentCliWriters.writeToPrintStream(out, writer -> {
                        switch (profile) {
                            case DEFAULT, CLEAN -> TrustDocumentCliWriters.writeMarkdownClean(trust, writer);
                            case ANCHORED -> TrustDocumentCliWriters.writeMarkdownAnchored(trust, writer);
                            case REVIEW -> TrustDocumentCliWriters.writeMarkdownReview(trust, writer);
                            default ->
                                throw new UsageException(
                                        "parse profile " + profile.flag + " is not valid for markdown format");
                        }
                    });
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private boolean writeTrustDocumentWithWriterPath(TrustDocument trust) throws CliException {
            switch (format) {
                case TRUST_JSON -> {
                    TrustDocumentCliWriters.writeToFile(out, writer -> {
                        switch (profile) {
                            case DEFAULT, FULL -> TrustDocumentCliWriters.writeJsonFull(trust, writer);
                            case EVIDENCE -> TrustDocumentCliWriters.writeJsonEvidence(trust, writer);
                            default ->
                                throw new UsageException(
                                        "parse profile " + profile.flag + " is not valid for json format");
                        }
                    });
                    return true;
                }
                case TRUST_AUDIT -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeAuditJson(trust, writer));
                    return true;
                }
                case TRUST_JSONL -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeJsonLines(trust, writer));
                    return true;
                }
                case TRUST_COMPACT -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeCompactLlm(trust, writer));
                    return true;
                }
                case TRUST_PLAIN -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writePlainText(trust, writer));
                    return true;
                }
                case TRUST_CONTENT_BLOCKS -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeContentBlocks(trust, writer));
                    return true;
                }
                case TRUST_PARSE_TRACE -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeParseTrace(trust, writer));
                    return true;
                }
                case TRUST_HTML -> {
                    TrustDocumentCliWriters.writeToFile(
                            out, writer -> TrustDocumentCliWriters.writeHtmlReview(trust, writer));
                    return true;
                }
                case TRUST_MARKDOWN -> {
                    TrustDocumentCliWriters.writeToFile(out, writer -> {
                        switch (profile) {
                            case DEFAULT, CLEAN -> TrustDocumentCliWriters.writeMarkdownClean(trust, writer);
                            case ANCHORED -> TrustDocumentCliWriters.writeMarkdownAnchored(trust, writer);
                            case REVIEW -> TrustDocumentCliWriters.writeMarkdownReview(trust, writer);
                            default ->
                                throw new UsageException(
                                        "parse profile " + profile.flag + " is not valid for markdown format");
                        }
                    });
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        TrustDocument parseTrustDocument() throws CliException {
            if (!isPdf(document)) {
                throw new CliException("unsupported document format: " + document);
            }
            var backendName = backend == ParserBackendChoice.PDFBOX ? "pdfbox" : "sidecar";
            var parserRun = preset.parserRun(backendName);
            var request = new ParserRequest(
                    document,
                    sourceHash(document),
                    parserRun,
                    preset.runtimePolicy().offlineMode(),
                    preset.runtimePolicy().allowModelDownloads());
            try {
                if (backend == ParserBackendChoice.PDFBOX) {
                    return new PdfBoxParserBackend().parse(request).withEvaluatedAuditGrade();
                }
                return new SidecarParserBackend(requiredRuntime())
                        .parse(request)
                        .withEvaluatedAuditGrade();
            } catch (ai.doctruth.ParseException e) {
                throw new CliException(backendName + " parser failed: " + e.errorCode() + ": " + e.getMessage(), e);
            }
        }

        private Path requiredRuntime() throws ai.doctruth.ParseException {
            if (runtime != null) {
                return runtime;
            }
            return DocTruthRuntime.requireConfiguredCommand(document);
        }

        void writeSourceMapIfRequested(TrustDocument trust) throws CliException {
            if (!sourceMap) {
                return;
            }
            TrustDocumentCliWriters.writeToFile(sourceMapPath(out), writer -> {
                switch (format) {
                    case TRUST_MARKDOWN -> TrustDocumentCliWriters.writeMarkdownSourceMap(trust, writer);
                    case TRUST_COMPACT -> TrustDocumentCliWriters.writeCompactLlmSourceMap(trust, writer);
                    default ->
                        throw new UsageException("--source-map is only supported with --format markdown or compact");
                }
            });
        }

        private String json(TrustDocument trust) {
            return switch (profile) {
                case DEFAULT, FULL -> trust.toJsonFull();
                case EVIDENCE -> trust.toJsonEvidence();
                default -> throw new UsageException("parse profile " + profile.flag + " is not valid for json format");
            };
        }

        private String markdown(TrustDocument trust) {
            return switch (profile) {
                case DEFAULT, CLEAN -> trust.toMarkdownClean();
                case ANCHORED -> trust.toMarkdownAnchored();
                case REVIEW -> trust.toMarkdownReview();
                default ->
                    throw new UsageException("parse profile " + profile.flag + " is not valid for markdown format");
            };
        }

        private TrustDocument trust(ai.doctruth.ParsedDocument doc) throws CliException {
            return TrustDocument.fromParsed(doc, sourceHash(document), preset.parserRun("pdfbox"))
                    .withEvaluatedAuditGrade();
        }

        private static void validate(
                OutputFormat format,
                OutputProfile profile,
                boolean sourceMap,
                Path out,
                ParserBackendChoice backend,
                Path runtime) {
            if (sourceMap && out == null) {
                throw new UsageException("--source-map requires --out");
            }
            if (sourceMap && format != OutputFormat.TRUST_MARKDOWN && format != OutputFormat.TRUST_COMPACT) {
                throw new UsageException("--source-map is only supported with --format markdown or compact");
            }
            if (format != OutputFormat.TRUST_MARKDOWN
                    && format != OutputFormat.TRUST_JSON
                    && profile != OutputProfile.DEFAULT) {
                throw new UsageException(
                        "parse profile " + profile.flag + " is only valid for markdown or json formats");
            }
            if (format != OutputFormat.TRUST_MARKDOWN && profile == OutputProfile.ANCHORED) {
                throw new UsageException("parse profile anchored is only valid for markdown format");
            }
            if (runtime != null && backend == ParserBackendChoice.PDFBOX) {
                throw new UsageException("--runtime cannot be combined with --backend pdfbox");
            }
            if ((format == OutputFormat.LEGACY_JSON || format == OutputFormat.LEGACY_MARKDOWN)
                    && backend != ParserBackendChoice.PDFBOX) {
                throw new UsageException(
                        "legacy parse output requires --backend pdfbox; use --json/--markdown for Rust TrustDocument output");
            }
        }

        private static Path defaultRuntime(Map<String, String> env) {
            return DocTruthRuntime.configuredCommand(env).orElse(null);
        }

        private static ParserPreset parserPreset(String value) {
            try {
                return ParserPreset.fromId(value);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }

        private static OutputFormat chooseFormat(OutputFormat current, OutputFormat requested, String option) {
            if (current != OutputFormat.SUMMARY && current != requested) {
                throw new UsageException(option + " cannot be combined with another parse output format");
            }
            return requested;
        }

        private static Path sourceMapPath(Path out) {
            String name = out.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stem = dot < 0 ? name : name.substring(0, dot);
            return out.resolveSibling(stem + ".doctruth-map.json");
        }

        private static String sourceHash(Path document) throws CliException {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(document)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return "sha256:" + HexFormat.of().formatHex(digest.digest());
            } catch (IOException e) {
                throw new CliException("failed to hash source document: " + e.getMessage(), e);
            } catch (NoSuchAlgorithmException e) {
                throw new CliException("SHA-256 is unavailable", e);
            }
        }

        private static boolean isPdf(Path document) {
            String name = document.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".pdf");
        }
    }

    static String sourceHashForFile(Path document) throws CliException {
        return ParseOptions.sourceHash(document);
    }

    private enum ParserBackendChoice {
        AUTO,
        PDFBOX,
        SIDECAR;

        static ParserBackendChoice from(String value) {
            return switch (value) {
                case "auto" -> AUTO;
                case "pdfbox" -> PDFBOX;
                case "sidecar" -> SIDECAR;
                default -> throw new UsageException("unknown parser backend: " + value);
            };
        }
    }

    private enum OutputFormat {
        SUMMARY,
        LEGACY_JSON,
        LEGACY_MARKDOWN,
        TRUST_JSON,
        TRUST_MARKDOWN,
        TRUST_PLAIN,
        TRUST_HTML,
        TRUST_JSONL,
        TRUST_AUDIT,
        TRUST_COMPACT,
        TRUST_CONTENT_BLOCKS,
        TRUST_PARSE_TRACE;

        static OutputFormat from(String value) {
            return switch (value) {
                case "json" -> TRUST_JSON;
                case "markdown", "md" -> TRUST_MARKDOWN;
                case "legacy-json", "legacy_json" -> LEGACY_JSON;
                case "legacy-markdown", "legacy_markdown", "legacy-md", "legacy_md" -> LEGACY_MARKDOWN;
                case "plain", "text", "txt" -> TRUST_PLAIN;
                case "html" -> TRUST_HTML;
                case "jsonl" -> TRUST_JSONL;
                case "audit" -> TRUST_AUDIT;
                case "compact", "compact_llm" -> TRUST_COMPACT;
                case "content_blocks", "content-blocks" -> TRUST_CONTENT_BLOCKS;
                case "parse_trace", "parse-trace" -> TRUST_PARSE_TRACE;
                default -> throw new UsageException("unknown parse format: " + value);
            };
        }
    }

    private enum OutputProfile {
        DEFAULT("default"),
        FULL("full"),
        EVIDENCE("evidence"),
        CLEAN("clean"),
        ANCHORED("anchored"),
        REVIEW("review");

        private final String flag;

        OutputProfile(String flag) {
            this.flag = flag;
        }

        static OutputProfile from(String value) {
            return switch (value) {
                case "default" -> DEFAULT;
                case "full" -> FULL;
                case "evidence" -> EVIDENCE;
                case "clean" -> CLEAN;
                case "anchored" -> ANCHORED;
                case "review" -> REVIEW;
                default -> throw new UsageException("unknown parse profile: " + value);
            };
        }
    }
}
