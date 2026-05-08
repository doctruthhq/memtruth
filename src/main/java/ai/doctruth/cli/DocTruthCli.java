package ai.doctruth.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import ai.doctruth.JsonSchema;
import ai.doctruth.internal.schema.JsonSchemaCompatibility;

/**
 * Minimal command-line entry point for build-time migration helpers. Runtime library
 * users do not need this class, and production extraction does not depend on Python.
 */
public final class DocTruthCli {

    private final PrintStream out;
    private final PrintStream err;
    private final PydanticExporter exporter;

    public DocTruthCli() {
        this(System.getenv(), System.out, System.err, new PythonPydanticExporter(System.getenv()));
    }

    DocTruthCli(Map<String, String> env, PrintStream out, PrintStream err, PydanticExporter exporter) {
        Objects.requireNonNull(env, "env");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    public static void main(String[] args) {
        System.exit(new DocTruthCli().run(args));
    }

    int run(String[] args) {
        try {
            return runChecked(args);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        } catch (MigrationException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private int runChecked(String[] args) throws MigrationException {
        if (args.length < 1 || "--help".equals(args[0])) {
            throw new UsageException(usage());
        }
        if (args.length >= 3 && "migrate".equals(args[0]) && "pydantic".equals(args[1])) {
            migratePydantic(args);
            return 0;
        }
        throw new UsageException(usage());
    }

    private void migratePydantic(String[] args) throws MigrationException {
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
        out.println(options.check() ? "schema compatible: " + options.out() : "schema exported: " + options.out());
    }

    private String exportSchema(String spec) throws MigrationException {
        try {
            return exporter.export(spec);
        } catch (IOException e) {
            throw new MigrationException("failed to export Pydantic schema: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("Pydantic schema export interrupted", e);
        }
    }

    private static JsonSchema readSchema(String schemaJson) throws MigrationException {
        try {
            return JsonSchema.from(schemaJson);
        } catch (IllegalArgumentException e) {
            throw new MigrationException("exported Pydantic schema is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static void checkCompatible(JsonSchema schema) throws MigrationException {
        var errors = JsonSchemaCompatibility.check(schema.node());
        if (!errors.isEmpty()) {
            throw new MigrationException("schema compatibility check failed: " + String.join("; ", errors));
        }
    }

    private static void writeSchema(Path out, String schemaJson) throws MigrationException {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, schemaJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException("failed to write schema to " + out + ": " + e.getMessage(), e);
        }
    }

    private static String usage() {
        return "usage: doctruth migrate pydantic <module>:<Model> --out <schema.json> [--check]";
    }

    @FunctionalInterface
    interface PydanticExporter {
        String export(String spec) throws IOException, InterruptedException;
    }

    private record MigrationOptions(Path out, boolean check) {
        static MigrationOptions parse(String[] args) {
            Path out = null;
            boolean check = false;
            for (int i = 0; i < args.length; i++) {
                if ("--check".equals(args[i])) {
                    check = true;
                } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                    out = Path.of(args[++i]);
                } else {
                    throw new UsageException("unknown or incomplete option: " + args[i]);
                }
            }
            if (out == null) {
                throw new UsageException("--out <schema.json> is required");
            }
            return new MigrationOptions(out, check);
        }
    }

    static final class PythonPydanticExporter implements PydanticExporter {
        private static final String SCRIPT = """
                import importlib, json, sys
                spec = sys.argv[1]
                module_name, _, model_name = spec.partition(":")
                model = getattr(importlib.import_module(module_name), model_name)
                print(json.dumps(model.model_json_schema(), ensure_ascii=False, separators=(",", ":")))
                """;

        private final Map<String, String> env;

        PythonPydanticExporter(Map<String, String> env) {
            this.env = Map.copyOf(env);
        }

        @Override
        public String export(String spec) throws IOException, InterruptedException {
            String python = env.getOrDefault("DOCTRUTH_PYTHON", "python3");
            Process process = new ProcessBuilder(python, "-c", SCRIPT, spec).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("python exited " + exit + ": " + stderr.strip());
            }
            return stdout;
        }
    }

    private static class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UsageException(String message) {
            super(message);
        }
    }

    private static class MigrationException extends Exception {
        private static final long serialVersionUID = 1L;

        private MigrationException(String message) {
            super(message);
        }

        private MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
