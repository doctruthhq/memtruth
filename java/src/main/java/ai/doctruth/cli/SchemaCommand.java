package ai.doctruth.cli;

import java.io.UncheckedIOException;
import java.nio.file.Path;

import ai.doctruth.JsonSchema;

final class SchemaCommand {

    private final CliContext context;

    SchemaCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = SchemaOptions.parse(args);
        var schema = readSchema(options.path());
        var summary = SchemaSummary.from(schema);
        if (!summary.compatible()) {
            throw new CliException("schema compatibility check failed: " + String.join("; ", summary.errors()));
        }
        if (options.json()) {
            context.out().println(summary.toJson());
            return;
        }
        context.out().println("schema compatible: " + options.path());
        context.out().println("fields: " + summary.fieldCount());
        context.out().println("required: " + summary.requiredCount());
    }

    static JsonSchema readSchema(Path path) throws CliException {
        try {
            return JsonSchema.from(path);
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new CliException("failed to read schema " + path + ": " + e.getMessage(), e);
        }
    }

    private record SchemaOptions(Path path, boolean json) {
        static SchemaOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth schema <schema.json> [--json]");
            }
            Path path = Path.of(args[1]);
            boolean json = false;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                if ("--json".equals(arg)) {
                    json = true;
                } else {
                    throw new UsageException("unknown schema option: " + arg);
                }
            }
            return new SchemaOptions(path, json);
        }
    }
}
