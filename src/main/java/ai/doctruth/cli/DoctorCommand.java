package ai.doctruth.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class DoctorCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> PROVIDER_KEYS = Map.of(
            "OPENAI_API_KEY",
            "OpenAI / OpenAI-compatible",
            "ANTHROPIC_API_KEY",
            "Anthropic",
            "GOOGLE_API_KEY",
            "Gemini",
            "DEEPSEEK_API_KEY",
            "DeepSeek");

    private final CliContext context;

    DoctorCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        boolean json = false;
        var cursor = new ArgCursor(args, 1);
        while (cursor.hasNext()) {
            String arg = cursor.next();
            if ("--json".equals(arg)) {
                json = true;
            } else {
                throw new UsageException("unknown doctor option: " + arg);
            }
        }

        var report = DoctorReport.create(context.env());
        if (json) {
            context.out().println(report.toJson());
        } else {
            context.out().print(report.toText());
        }
    }

    private record DoctorReport(
            String javaVersion,
            int javaFeature,
            boolean javaSupported,
            boolean projectConfig,
            boolean outputDir,
            Map<String, Boolean> env,
            boolean ready) {

        static DoctorReport create(Map<String, String> env) {
            var keys = PROVIDER_KEYS.keySet().stream()
                    .sorted()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(k -> k, k -> isSet(env.get(k))));
            int feature = Runtime.version().feature();
            boolean javaOk = feature >= 25;
            boolean config = Files.exists(Path.of("doctruth.yml"));
            boolean output = Files.exists(Path.of(".doctruth/runs"));
            boolean hasProvider = keys.values().stream().anyMatch(Boolean::booleanValue);
            return new DoctorReport(
                    System.getProperty("java.version"), feature, javaOk, config, output, keys, javaOk && hasProvider);
        }

        String toText() {
            var text = new StringBuilder()
                    .append("DocTruth doctor\n")
                    .append("java: ")
                    .append(javaVersion)
                    .append(javaSupported ? " ok" : " needs Java 25+")
                    .append('\n')
                    .append("project: ")
                    .append(projectConfig ? "doctruth.yml found" : "run `doctruth init` to create doctruth.yml")
                    .append('\n')
                    .append("runs: ")
                    .append(outputDir ? ".doctruth/runs found" : "created by `doctruth init` or first extraction")
                    .append('\n');
            env.forEach((key, set) -> text.append(key)
                    .append(": ")
                    .append(set ? "set" : "missing")
                    .append('\n'));
            return text.append("ready: ")
                    .append(ready ? "yes" : "no")
                    .append('\n')
                    .toString();
        }

        String toJson() throws CliException {
            try {
                return MAPPER.writeValueAsString(Map.of(
                        "java",
                        Map.of("version", javaVersion, "feature", javaFeature, "supported", javaSupported),
                        "project",
                        Map.of("config", projectConfig, "runsDirectory", outputDir),
                        "env",
                        env,
                        "ready",
                        ready));
            } catch (JsonProcessingException e) {
                throw new CliException("failed to render doctor JSON: " + e.getMessage(), e);
            }
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }
}
