package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import ai.doctruth.ParseException;
import ai.doctruth.ParserPreset;
import ai.doctruth.PdfPageImageRenderer;
import ai.doctruth.TrustDocument;
import ai.doctruth.TrustDocumentBody;
import ai.doctruth.TrustDocumentParser;
import ai.doctruth.TrustPage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ReviewPackageCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    ReviewPackageCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var document = parse(options);
        var pages = renderPages(options);
        document = withRenderedPageHashes(document, pages);
        writeDocument(options, document);
        writeLayeredArtifacts(options, document);
        writeManifest(options, pages);
        writeReviewHtml(options, document, pages);
        context.out().println("review-package: " + options.out());
        context.out().println("pages: " + pages.size());
    }

    private static TrustDocument parse(Options options) throws CliException {
        try {
            return TrustDocumentParser.parse(options.document(), options.preset());
        } catch (ParseException e) {
            throw new CliException("failed to parse review package document: " + e.errorCode(), e);
        }
    }

    private static void writeDocument(Options options, TrustDocument document) throws CliException {
        TrustDocumentCliWriters.writeToFile(options.out().resolve("trust-document.json"), document::writeJsonFull);
    }

    private static void writeLayeredArtifacts(Options options, TrustDocument document) throws CliException {
        TrustDocumentCliWriters.writeToFile(
                options.out().resolve("content_blocks.json"), writer -> TrustDocumentCliWriters.writeContentBlocks(document, writer));
        TrustDocumentCliWriters.writeToFile(
                options.out().resolve("parse_trace.json"), writer -> TrustDocumentCliWriters.writeParseTrace(document, writer));
        TrustDocumentCliWriters.writeToFile(
                options.out().resolve("layout-debug.html"), writer -> TrustDocumentCliWriters.writeLayoutDebugHtml(document, writer));
        TrustDocumentCliWriters.writeToFile(
                options.out().resolve("span-debug.html"), writer -> TrustDocumentCliWriters.writeSpanDebugHtml(document, writer));
    }

    private static TrustDocument withRenderedPageHashes(TrustDocument document, java.util.List<TrustPage> renderedPages) {
        Map<Integer, TrustPage> renderedByPage =
                renderedPages.stream().collect(Collectors.toMap(TrustPage::pageNumber, page -> page));
        var pages = new ArrayList<TrustPage>();
        for (var page : document.body().pages()) {
            var rendered = renderedByPage.get(page.pageNumber());
            pages.add(rendered == null ? page : rendered);
        }
        var body = new TrustDocumentBody(pages, document.body().units(), document.body().tables());
        return new TrustDocument(
                document.docId(), document.source(), body, document.parserRun(), document.auditGradeStatus());
    }

    private static java.util.List<TrustPage> renderPages(Options options) throws CliException {
        try {
            return PdfPageImageRenderer.writePngs(options.document(), options.pagesDir());
        } catch (ParseException e) {
            throw new CliException("failed to render review package pages: " + e.errorCode(), e);
        }
    }

    private static void writeManifest(Options options, java.util.List<TrustPage> pages) throws CliException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("sourceFilename", options.document().getFileName().toString());
        root.put("outputDir", options.pagesDir().toString());
        ArrayNode nodes = MAPPER.createArrayNode();
        for (var page : pages) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("pageNumber", page.pageNumber());
            node.put("width", page.width());
            node.put("height", page.height());
            node.put("textLayerAvailable", page.textLayerAvailable());
            node.put("imageHash", page.imageHash());
            node.put("path", "page-%04d.png".formatted(page.pageNumber()));
            nodes.add(node);
        }
        root.set("pages", nodes);
        writeJson(options.pagesDir().resolve("page-images.json"), root);
    }

    private static void writeReviewHtml(
            Options options, TrustDocument document, java.util.List<TrustPage> pages) throws CliException {
        try {
            Files.createDirectories(options.out());
            try (var writer = Files.newBufferedWriter(options.out().resolve("review.html"))) {
                writer.write("<!doctype html>\n<html><body data-trust-review-package=\"doctruth\">\n");
                for (var page : pages) {
                    writer.write("<img src=\"pages/page-%04d.png\" data-trust-page-image-for=\"%d\" alt=\"page %d\">\n"
                            .formatted(page.pageNumber(), page.pageNumber(), page.pageNumber()));
                }
                document.writeHtmlReview(writer);
                writer.write("</body></html>\n");
            }
        } catch (IOException e) {
            throw new CliException("failed to write review package HTML: " + e.getMessage(), e);
        }
    }

    private static void writeJson(Path path, ObjectNode node) throws CliException {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(Files.newBufferedWriter(path), node);
        } catch (IOException e) {
            throw new CliException("failed to write review package manifest: " + e.getMessage(), e);
        }
    }

    private record Options(Path document, Path out, ParserPreset preset) {

        Path pagesDir() {
            return out.resolve("pages");
        }

        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth review-package <document> [--preset <preset>] -o <dir>");
            }
            Path document = Path.of(args[1]);
            Path out = null;
            ParserPreset preset = ParserPreset.LITE;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--preset" -> preset = parserPreset(cursor.next());
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown review-package option: " + arg);
                }
            }
            if (out == null) {
                throw new UsageException("review-package requires -o <dir>");
            }
            return new Options(document, out, preset);
        }

        private static ParserPreset parserPreset(String value) {
            try {
                return ParserPreset.fromId(value);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }
    }
}
