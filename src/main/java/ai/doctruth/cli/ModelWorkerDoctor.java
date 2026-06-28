package ai.doctruth.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

record ModelWorkerDoctor(String command, boolean available, long timeoutMs, Readiness readiness) {

    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static ModelWorkerDoctor local(Map<String, String> env) {
        Optional<String> command = setting(env, "DOCTRUTH_MODEL_COMMAND", "LOCAL_MODEL_COMMAND")
                .flatMap(value -> resolveExecutable(value, env));
        var readiness = command.map(value -> doctor(value, timeoutMs(env))).orElse(Readiness.missing());
        return new ModelWorkerDoctor(command.orElse(""), command.isPresent(), timeoutMs(env), readiness);
    }

    String summary() {
        if (!available) {
            return "missing";
        }
        return ready() ? command + " ready" : command + " not ready: " + statusCode();
    }

    boolean ready() {
        return readiness.ready();
    }

    String statusCode() {
        return readiness.code();
    }

    String message() {
        return readiness.message();
    }

    List<String> loadedModels() {
        return readiness.loadedModels();
    }

    long rssMb() {
        return readiness.resources().rssMb();
    }

    long peakMemoryMb() {
        return readiness.resources().peakMemoryMb();
    }

    private static Readiness doctor(String command, long timeoutMs) {
        try {
            var process = new ProcessBuilder(command, "--doctor")
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            if (!process.waitFor(Math.min(timeoutMs, 5_000), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Readiness.notReady("worker_doctor_timeout", "worker --doctor timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (stdout.isBlank()) {
                return Readiness.notReady("worker_doctor_empty", truncate(stderr));
            }
            var json = MAPPER.readTree(stdout);
            boolean ok = json.path("ok").asBoolean(false);
            String code = ok ? "ready" : json.path("code").asText("worker_not_ready");
            String message = json.path("message").asText(ok ? "ready" : "");
            var models = json.path("loadedModels").isArray()
                    ? MAPPER.convertValue(json.path("loadedModels"), StringListType.VALUE)
                    : List.<String>of();
            var resources = new ResourceUsage(
                    positiveLong(json.path("rssMb").asLong(0)),
                    positiveLong(json.path("peakMemoryMb").asLong(0)));
            return new Readiness(ok, code, message, models, resources);
        } catch (IOException e) {
            return Readiness.notReady("worker_doctor_unavailable", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Readiness.notReady("worker_doctor_interrupted", e.getMessage());
        } catch (RuntimeException e) {
            return Readiness.notReady("worker_doctor_protocol_error", e.getMessage());
        }
    }

    private static Optional<String> resolveExecutable(String command, Map<String, String> env) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        String trimmed = command.strip();
        if (trimmed.contains("/") || trimmed.startsWith(".")) {
            return executable(Path.of(trimmed)).map(Path::toString);
        }
        String path = env.getOrDefault("PATH", System.getenv("PATH"));
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Optional<Path> resolved = executable(Path.of(dir, trimmed));
            if (resolved.isPresent()) {
                return Optional.of(resolved.get().toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
    }

    private static long timeoutMs(Map<String, String> env) {
        return setting(env, "DOCTRUTH_MODEL_TIMEOUT_MS", "LOCAL_MODEL_TIMEOUT_MS")
                .flatMap(ModelWorkerDoctor::parsePositiveLong)
                .orElse(DEFAULT_TIMEOUT_MS);
    }

    private static Optional<Long> parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static long positiveLong(long value) {
        return Math.max(0, value);
    }

    private static Optional<String> setting(Map<String, String> env, String primaryEnv, String secondaryEnv) {
        String primary = env.get(primaryEnv);
        if (primary != null && !primary.isBlank()) {
            return Optional.of(primary.strip());
        }
        String secondary = env.get(secondaryEnv);
        if (secondary != null && !secondary.isBlank()) {
            return Optional.of(secondary.strip());
        }
        return Optional.empty();
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record Readiness(
            boolean ready, String code, String message, List<String> loadedModels, ResourceUsage resources) {
        static Readiness missing() {
            return notReady("missing", "");
        }

        static Readiness notReady(String code, String message) {
            return new Readiness(false, code, message, List.of(), ResourceUsage.NONE);
        }
    }

    private record ResourceUsage(long rssMb, long peakMemoryMb) {
        private static final ResourceUsage NONE = new ResourceUsage(0, 0);
    }

    private static final class StringListType extends com.fasterxml.jackson.core.type.TypeReference<List<String>> {
        private static final StringListType VALUE = new StringListType();
    }
}
