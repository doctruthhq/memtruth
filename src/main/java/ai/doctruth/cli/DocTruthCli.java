package ai.doctruth.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import ai.doctruth.LlmProvider;

/**
 * Command-line entry point. The CLI is a developer onboarding surface; the library API
 * remains the production integration surface.
 */
public final class DocTruthCli {

    private final CliContext context;

    public DocTruthCli() {
        this(System.getenv(), System.out, System.err, new PythonPydanticExporter(System.getenv()), Providers::create);
    }

    DocTruthCli(
            Map<String, String> env,
            PrintStream out,
            PrintStream err,
            PydanticExporter exporter,
            ProviderFactory providers) {
        this.context = new CliContext(env, out, err, exporter, providers);
    }

    public static void main(String[] args) {
        System.exit(new DocTruthCli().run(args));
    }

    int run(String[] args) {
        try {
            return runChecked(args);
        } catch (UsageException e) {
            context.err().println(e.getMessage());
            context.err().println("Try: doctruth --help");
            return 2;
        } catch (CliException e) {
            context.err().println(e.getMessage());
            return 1;
        }
    }

    private int runChecked(String[] args) throws CliException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            context.out().println(Usage.main());
            return 0;
        }
        if ("--version".equals(args[0]) || "version".equals(args[0])) {
            context.out().println("DocTruth " + version());
            return 0;
        }
        switch (args[0]) {
            case "init" -> new InitCommand(context).run(args);
            case "parse" -> new ParseCommand(context).run(args);
            case "profile" -> new ProfileCommand(context).run(args);
            case "schema" -> new SchemaCommand(context).run(args);
            case "extract" -> new ExtractCommand(context).run(args);
            case "audit" -> new AuditCommand(context).run(args);
            case "migrate" -> new MigrateCommand(context).run(args);
            case "doctor" -> new DoctorCommand(context).run(args);
            case "completion" -> new CompletionCommand(context).run(args);
            default -> throw new UsageException("unknown command: " + args[0]);
        }
        return 0;
    }

    private static String version() {
        String value = DocTruthCli.class.getPackage().getImplementationVersion();
        return value == null || value.isBlank() ? "0.2.0-alpha" : value;
    }

    @FunctionalInterface
    interface PydanticExporter {
        String export(String spec) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ProviderFactory {
        LlmProvider create(ProviderConfig options) throws CliException;
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
            this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
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
}
