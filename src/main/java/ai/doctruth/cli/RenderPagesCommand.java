package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.doctruth.ParseException;
import ai.doctruth.PdfPageImageRenderer;
import ai.doctruth.TrustPage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class RenderPagesCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    RenderPagesCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var pages = render(options);
        writeManifest(options, pages);
        context.out().println("pages: " + pages.size());
        context.out().println("page-images: " + options.out());
    }

    private static java.util.List<TrustPage> render(Options options) throws CliException {
        try {
            return PdfPageImageRenderer.writePngs(options.document(), options.out());
        } catch (ParseException e) {
            throw new CliException("failed to render page images: " + e.errorCode() + ": " + e.getMessage(), e);
        }
    }

    private static void writeManifest(Options options, java.util.List<TrustPage> pages) throws CliException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("sourceFilename", options.document().getFileName().toString());
        root.put("outputDir", options.out().toString());
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
        try {
            MAPPER.writeValue(Files.newBufferedWriter(options.out().resolve("page-images.json")), root);
        } catch (IOException e) {
            throw new CliException("failed to write page image manifest: " + e.getMessage(), e);
        }
    }

    private record Options(Path document, Path out) {

        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth render-pages <document> -o <dir>");
            }
            Path document = Path.of(args[1]);
            Path out = null;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown render-pages option: " + arg);
                }
            }
            if (out == null) {
                throw new UsageException("render-pages requires -o <dir>");
            }
            return new Options(document, out);
        }
    }
}
