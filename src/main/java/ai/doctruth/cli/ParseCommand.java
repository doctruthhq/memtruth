package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ParseCommand {

    private final CliContext context;

    ParseCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = ParseOptions.parse(args);
        var doc = DocumentParsers.parse(options.document());
        String json = ParsedDocumentJson.toJson(doc);
        if (options.out() != null) {
            write(options.out(), json);
        }
        if (options.json() && options.out() == null) {
            context.out().println(json);
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

    private static void write(Path out, String json) throws CliException {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, json);
        } catch (IOException e) {
            throw new CliException("failed to write parsed JSON: " + e.getMessage(), e);
        }
    }

    private record ParseOptions(Path document, boolean json, boolean bboxes, Path out) {
        static ParseOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth parse <document> [--json] [--bboxes] [-o parsed.json]");
            }
            Path document = Path.of(args[1]);
            boolean json = false;
            boolean bboxes = false;
            Path out = null;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--json" -> json = true;
                    case "--bboxes" -> bboxes = true;
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown parse option: " + arg);
                }
            }
            return new ParseOptions(document, json, bboxes, out);
        }
    }
}
