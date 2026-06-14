package ai.doctruth.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

record OcrDoctor(
        String command,
        boolean available,
        boolean ready,
        boolean disabled,
        String engine,
        String fallbackEngine,
        long timeoutMs,
        String statusCode,
        String message) {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static OcrDoctor local(Map<String, String> env) {
        boolean disabled = disabled(env);
        Optional<String> command = disabled ? Optional.empty() : firstExecutable(commandCandidates(env), env);
        var readiness = command.map(value -> doctor(value, timeoutMs(env))).orElse(Readiness.missing());
        return new OcrDoctor(
                command.orElse(""),
                command.isPresent(),
                readiness.ready(),
                disabled,
                setting(env, "DOCTRUTH_OCR_ENGINE", "LOCAL_OCR_ENGINE").orElse("mnn"),
                setting(env, "DOCTRUTH_OCR_FALLBACK_ENGINE", "LOCAL_OCR_FALLBACK_ENGINE").orElse("onnxruntime"),
                timeoutMs(env),
                readiness.code(),
                readiness.message());
    }

    String summary() {
        if (disabled) {
            return "disabled";
        }
        if (!available) {
            return "missing";
        }
        return ready ? command + " ready" : command + " not ready: " + statusCode;
    }

    private static Readiness doctor(String command, long timeoutMs) {
        try {
            var process = new ProcessBuilder(command, "--doctor")
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            if (!process.waitFor(Math.min(timeoutMs, 5_000), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Readiness(false, "worker_doctor_timeout", "worker --doctor timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (stdout.isBlank()) {
                return new Readiness(false, "worker_doctor_empty", truncate(stderr));
            }
            JsonNode json = MAPPER.readTree(stdout);
            boolean ok = json.path("ok").asBoolean(false);
            String code = ok ? "ready" : json.path("code").asText("worker_not_ready");
            String message = json.path("message").asText(ok ? "ready" : "");
            return new Readiness(ok, code, message);
        } catch (IOException e) {
            return new Readiness(false, "worker_doctor_unavailable", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Readiness(false, "worker_doctor_interrupted", e.getMessage());
        } catch (RuntimeException e) {
            return new Readiness(false, "worker_doctor_protocol_error", e.getMessage());
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static boolean disabled(Map<String, String> env) {
        return setting(env, "DOCTRUTH_OCR_ENABLED", "LOCAL_OCR_ENABLED")
                .map(value -> value.equalsIgnoreCase("false") || value.equals("0"))
                .orElse(false);
    }

    private static List<String> commandCandidates(Map<String, String> env) {
        var explicit = setting(env, "DOCTRUTH_OCR_COMMAND", "LOCAL_OCR_COMMAND");
        if (explicit.isPresent()) {
            return List.of(explicit.get());
        }
        return List.of("doctruth-rapidocr-mnn-worker", "tradebot-ocr-worker-rs", "tradebot-ocr-worker");
    }

    private static Optional<String> firstExecutable(List<String> commands, Map<String, String> env) {
        for (String command : commands) {
            Optional<String> resolved = resolveExecutable(command, env);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
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
        return setting(env, "DOCTRUTH_OCR_TIMEOUT_MS", "LOCAL_OCR_TIMEOUT_MS")
                .flatMap(OcrDoctor::parsePositiveLong)
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

    private record Readiness(boolean ready, String code, String message) {
        static Readiness missing() {
            return new Readiness(false, "missing", "");
        }
    }
}
