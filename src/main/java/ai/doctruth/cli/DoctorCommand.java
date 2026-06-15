package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ai.doctruth.SidecarParserBackend;
import ai.doctruth.internal.runtime.DocTruthRuntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
        boolean modelsOnly = false;
        var cursor = new ArgCursor(args, 1);
        while (cursor.hasNext()) {
            String arg = cursor.next();
            if ("--json".equals(arg)) {
                json = true;
            } else if ("models".equals(arg)) {
                modelsOnly = true;
            } else {
                throw new UsageException("unknown doctor option: " + arg);
            }
        }

        var report = DoctorReport.create(context.env());
        if (json) {
            context.out().println(report.toJson());
        } else if (modelsOnly) {
            context.out().print(report.toModelText());
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
            ParserDoctor parser,
            ModelDoctor models,
            OcrDoctor ocr,
            MemoryDoctor memory,
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
            var parser = ParserDoctor.from(env);
            var models = ModelDoctor.local(env);
            var ocr = OcrDoctor.local(env);
            var memory = MemoryDoctor.current();
            return new DoctorReport(
                    System.getProperty("java.version"),
                    feature,
                    javaOk,
                    config,
                    output,
                    parser,
                    models,
                    ocr,
                    memory,
                    keys,
                    javaOk && hasProvider && parser.available());
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
                    .append('\n')
                    .append("parser backend: ")
                    .append(parser.backend())
                    .append(parser.available() ? " ok" : " unavailable")
                    .append('\n')
                    .append("model cache: ")
                    .append(models.cacheDirectory())
                    .append('\n')
                    .append("model worker: ")
                    .append(models.worker().summary())
                    .append(" (timeoutMs=")
                    .append(models.worker().timeoutMs())
                    .append(")")
                    .append('\n')
                    .append("ocr worker: ")
                    .append(ocr.summary())
                    .append(" (engine=")
                    .append(ocr.engine())
                    .append(", fallback=")
                    .append(ocr.fallbackEngine())
                    .append(", timeoutMs=")
                    .append(ocr.timeoutMs())
                    .append(")")
                    .append('\n')
                    .append("memory max: ")
                    .append(memory.maxMb())
                    .append(" MB")
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

        String toModelText() {
            return new StringBuilder()
                    .append("DocTruth model doctor\n")
                    .append("model cache: ")
                    .append(models.cacheDirectory())
                    .append(models.cacheExists() ? " found" : " missing")
                    .append('\n')
                    .append("required models: ")
                    .append(models.requiredModels())
                    .append('\n')
                    .append("network access required: ")
                    .append(models.networkAccessRequired() ? "yes" : "no")
                    .append('\n')
                    .append("model cache ready: ")
                    .append(models.allReady() ? "yes" : "no")
                    .append('\n')
                    .append("estimated model cache size: ")
                    .append(models.estimatedCacheMb())
                    .append(" MB")
                    .append('\n')
                    .append("model worker: ")
                    .append(models.worker().summary())
                    .append(" (timeoutMs=")
                    .append(models.worker().timeoutMs())
                    .append(")")
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
                        "parser",
                        Map.of(
                                "backend",
                                parser.backend(),
                                "available",
                                parser.available(),
                                "outputProfiles",
                                parser.outputProfiles(),
                                "runtimeDoctor",
                                parser.runtimeDoctor()),
                        "models",
                        Map.of(
                                "cacheDirectory",
                                models.cacheDirectory().toString(),
                                "cacheExists",
                                models.cacheExists(),
                                "requiredModels",
                                models.requiredModels(),
                                "networkAccessRequired",
                                models.networkAccessRequired(),
                                "allReady",
                                models.allReady(),
                                "estimatedCacheMb",
                                models.estimatedCacheMb(),
                                "artifacts",
                                models.artifactSummaries(),
                                "worker",
                                Map.of(
                                        "command",
                                        models.worker().command(),
                                        "available",
                                        models.worker().available(),
                                        "ready",
                                        models.worker().ready(),
                                        "timeoutMs",
                                        models.worker().timeoutMs(),
                                        "statusCode",
                                        models.worker().statusCode(),
                                        "message",
                                        models.worker().message(),
                                        "rssMb",
                                        models.worker().rssMb(),
                                        "peakMemoryMb",
                                        models.worker().peakMemoryMb(),
                                        "loadedModels",
                                        models.worker().loadedModels())),
                        "ocr",
                        Map.of(
                                "command",
                                ocr.command(),
                                "available",
                                ocr.available(),
                                "ready",
                                ocr.ready(),
                                "disabled",
                                ocr.disabled(),
                                "engine",
                                ocr.engine(),
                                "fallbackEngine",
                                ocr.fallbackEngine(),
                                "timeoutMs",
                                ocr.timeoutMs(),
                                "statusCode",
                                ocr.statusCode(),
                                "message",
                                ocr.message()),
                        "memory",
                        Map.of("maxMb", memory.maxMb(), "freeMb", memory.freeMb(), "totalMb", memory.totalMb()),
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

    private record ParserDoctor(String backend, boolean available, List<String> outputProfiles, JsonNode runtimeDoctor) {
        static ParserDoctor from(Map<String, String> env) {
            var runtime = DocTruthRuntime.configuredCommand(env);
            if (runtime.isEmpty()) {
                return unavailable();
            }
            if (!Files.isRegularFile(runtime.get())) {
                return unavailable();
            }
            var backend = new SidecarParserBackend(runtime.get());
            var capabilities = backend.capabilities();
            var health = backend.doctor();
            return new ParserDoctor("sidecar", health.available(), capabilities.outputProfiles(), runtimeDoctor(runtime.get(), env));
        }

        private static ParserDoctor unavailable() {
            return new ParserDoctor("sidecar", false, List.of(), MAPPER.createObjectNode());
        }

        private static JsonNode runtimeDoctor(Path runtime, Map<String, String> env) {
            try {
                var process = new ProcessBuilder(runtime.toString(), "--doctor");
                process.environment().putAll(env);
                var child = process.start();
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    return MAPPER.createObjectNode();
                }
                if (child.exitValue() != 0) {
                    return MAPPER.createObjectNode();
                }
                var stdout = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return MAPPER.readTree(stdout);
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return MAPPER.createObjectNode();
            }
        }
    }

    private record MemoryDoctor(long maxMb, long totalMb, long freeMb) {
        static MemoryDoctor current() {
            Runtime runtime = Runtime.getRuntime();
            return new MemoryDoctor(toMb(runtime.maxMemory()), toMb(runtime.totalMemory()), toMb(runtime.freeMemory()));
        }

        private static long toMb(long bytes) {
            return Math.max(1, bytes / (1024 * 1024));
        }
    }
}
