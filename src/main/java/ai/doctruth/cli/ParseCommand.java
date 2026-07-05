package ai.doctruth.cli;

import java.nio.file.Path;

import ai.doctruth.ParsedDocument;
import ai.doctruth.PdfParserBackend;
import ai.doctruth.TrustDocumentJson;

final class ParseCommand {

    private final CliContext context;
    private final Parser parser;
    private final JsonRenderer jsonRenderer;

    ParseCommand(CliContext context) {
        this(context, DocumentParsers::parse, new JsonRenderer() {
            @Override
            public String render(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument)
                    throws CliException {
                return renderJson(doc, source, parser, trustDocument);
            }

            @Override
            public void write(
                    ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument, Path out)
                    throws CliException {
                writeJson(doc, source, parser, trustDocument, out);
            }
        });
    }

    ParseCommand(CliContext context, Parser parser, JsonRenderer jsonRenderer) {
        this.context = context;
        this.parser = parser;
        this.jsonRenderer = jsonRenderer;
    }

    void run(String[] args) throws CliException {
        var options = ParseOptions.parse(args);
        var doc = parser.parse(options.document(), options.parser());
        if (options.out() != null) {
            writeJson(doc, options);
        }
        if (options.shouldPrintJson() && options.out() == null) {
            context.out().println(json(doc, options));
            return;
        }
        printSummary(options.document(), doc, options);
    }

    private String json(ParsedDocument doc, ParseOptions options) throws CliException {
        return jsonRenderer.render(
                doc, options.document(), options.parser(), options.format() == OutputFormat.TRUST_DOCUMENT_JSON);
    }

    private void writeJson(ParsedDocument doc, ParseOptions options) throws CliException {
        jsonRenderer.write(
                doc,
                options.document(),
                options.parser(),
                options.format() == OutputFormat.TRUST_DOCUMENT_JSON,
                options.out());
    }

    private static String renderJson(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument)
            throws CliException {
        if (trustDocument) {
            return TrustDocumentJson.toJson(doc, source, parser);
        }
        return ParsedDocumentJson.toJson(doc);
    }

    private static void writeJson(
            ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument, Path out)
            throws CliException {
        if (trustDocument) {
            try {
                TrustDocumentJson.writeJson(doc, source, parser, out);
            } catch (IllegalStateException e) {
                throw new CliException("failed to write TrustDocument JSON: " + e.getMessage(), e);
            }
            return;
        }
        ParsedDocumentJson.writeJson(doc, out);
    }

    private void printSummary(Path source, ai.doctruth.ParsedDocument doc, ParseOptions options) {
        var stats = ParsedDocumentStats.from(doc);
        context.out().println(source);
        context.out().println("parser: " + options.parser().id());
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

    @FunctionalInterface
    interface Parser {
        ParsedDocument parse(Path path, PdfParserBackend parser) throws CliException;
    }

    interface JsonRenderer {
        String render(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument)
                throws CliException;

        void write(ParsedDocument doc, Path source, PdfParserBackend parser, boolean trustDocument, Path out)
                throws CliException;
    }

    enum OutputFormat {
        PARSED_JSON,
        TRUST_DOCUMENT_JSON
    }

    private record ParseOptions(
            Path document, boolean legacyJson, boolean bboxes, Path out, PdfParserBackend parser, OutputFormat format) {
        static ParseOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException(
                        "usage: doctruth parse <document> [--parser opendataloader|pdfbox] [--format json|parsed-json] [--json] [--bboxes] [-o parsed.json]");
            }
            Path document = Path.of(args[1]);
            boolean json = false;
            boolean bboxes = false;
            Path out = null;
            PdfParserBackend parser = PdfParserBackend.OPENDATALOADER;
            OutputFormat format = OutputFormat.PARSED_JSON;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--json" -> json = true;
                    case "--bboxes" -> bboxes = true;
                    case "--format" -> format = parseFormat(nextValue(cursor, arg));
                    case "--parser" -> parser = parseBackend(nextValue(cursor, arg));
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown parse option: " + arg);
                }
            }
            return new ParseOptions(document, json, bboxes, out, parser, format);
        }

        boolean shouldPrintJson() {
            return legacyJson || format == OutputFormat.TRUST_DOCUMENT_JSON;
        }

        private static PdfParserBackend parseBackend(String raw) {
            try {
                return PdfParserBackend.fromId(raw);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }

        private static OutputFormat parseFormat(String raw) {
            return switch (raw) {
                case "json", "trust-json", "trust-document-json" -> OutputFormat.TRUST_DOCUMENT_JSON;
                case "parsed-json" -> OutputFormat.PARSED_JSON;
                default -> throw new UsageException("unsupported parse format: " + raw);
            };
        }

        private static String nextValue(ArgCursor cursor, String option) {
            if (!cursor.hasNext()) {
                throw new UsageException(option + " requires a value");
            }
            return cursor.next();
        }
    }
}
