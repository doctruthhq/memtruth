package ai.doctruth.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import ai.doctruth.JsonSchema;
import ai.doctruth.internal.schema.JsonSchemaCompatibility;

final class MigrateCommand {

    private final CliContext context;

    MigrateCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        if (args.length < 3 || !"pydantic".equals(args[1])) {
            throw new UsageException("usage: doctruth migrate pydantic <module>:<Model> -o <schema.json> [--check]");
        }
        migratePydantic(args);
    }

    private void migratePydantic(String[] args) throws CliException {
        String spec = args[2];
        if (!spec.contains(":")) {
            throw new UsageException("expected <module>:<Model> for pydantic migration");
        }
        var options = MigrationOptions.parse(Arrays.copyOfRange(args, 3, args.length));
        String schemaJson = exportSchema(spec);
        JsonSchema schema = readSchema(schemaJson);
        if (options.check()) {
            checkCompatible(schema);
        }
        writeSchema(options.out(), schemaJson);
        context.out()
                .println(options.check() ? "schema compatible: " + options.out() : "schema exported: " + options.out());
    }

    private String exportSchema(String spec) throws CliException {
        try {
            return context.exporter().export(spec);
        } catch (IOException e) {
            throw new CliException("failed to export Pydantic schema: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Pydantic schema export interrupted", e);
        }
    }

    private static JsonSchema readSchema(String schemaJson) throws CliException {
        try {
            return JsonSchema.from(schemaJson);
        } catch (IllegalArgumentException e) {
            throw new CliException("exported Pydantic schema is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static void checkCompatible(JsonSchema schema) throws CliException {
        var errors = JsonSchemaCompatibility.check(schema.node());
        if (!errors.isEmpty()) {
            throw new CliException("schema compatibility check failed: " + String.join("; ", errors));
        }
    }

    private static void writeSchema(Path out, String schemaJson) throws CliException {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, schemaJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("failed to write schema to " + out + ": " + e.getMessage(), e);
        }
    }

    private record MigrationOptions(Path out, boolean check) {
        static MigrationOptions parse(String[] args) {
            Path out = null;
            boolean check = false;
            for (int i = 0; i < args.length; i++) {
                if ("--check".equals(args[i])) {
                    check = true;
                } else if (("-o".equals(args[i]) || "--out".equals(args[i])) && i + 1 < args.length) {
                    out = Path.of(args[++i]);
                } else {
                    throw new UsageException("unknown or incomplete migrate option: " + args[i]);
                }
            }
            if (out == null) {
                throw new UsageException("-o <schema.json> is required");
            }
            return new MigrationOptions(out, check);
        }
    }
}
