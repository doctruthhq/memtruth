package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class IngestAuditCommand {

    private static final int DEFAULT_LIMIT = 2_000;

    private final CliContext context;
    private final IngestAuditRunner runner = new IngestAuditRunner();

    IngestAuditCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = Options.parse(args);
        var report = runner.run(options.root(), options.limit());
        String json = IngestAuditJson.toJson(report);
        if (options.out() != null) {
            write(options.out(), json);
        }
        if (options.json() && options.out() == null) {
            context.out().println(json);
            return;
        }
        printSummary(report, options);
    }

    private void printSummary(IngestAuditReport report, Options options) {
        context.out().println("ingest audit");
        context.out().println("root: " + report.root());
        context.out().println("total files: " + report.totalFiles());
        context.out().println("parsed: " + report.parsed());
        context.out().println("failed: " + report.failed());
        context.out().println("issues:");
        report.issueSummary().forEach((category, count) -> context.out().println("  " + category + ": " + count));
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
            throw new CliException("failed to write ingest audit JSON: " + e.getMessage(), e);
        }
    }

    private record Options(Path root, boolean json, int limit, Path out) {
        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth ingest-audit <pdf-dir> [--json] [--limit N] [-o audit.json]");
            }
            Path root = Path.of(args[1]);
            boolean json = false;
            int limit = DEFAULT_LIMIT;
            Path out = null;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--json" -> json = true;
                    case "--limit" -> limit = parseLimit(cursor.next(), arg);
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    default -> throw new UsageException("unknown ingest-audit option: " + arg);
                }
            }
            return new Options(root, json, limit, out);
        }

        private static int parseLimit(String value, String option) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1) {
                    throw new NumberFormatException("limit must be positive");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new UsageException(option + " requires a positive integer");
            }
        }
    }
}
