package ai.doctruth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LocalModelWorker {

    private static final Logger LOG = LoggerFactory.getLogger(LocalModelWorker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String command;

    LocalModelWorker(String command) {
        this.command = requireNonBlank(command);
    }

    Optional<TrustDocument> parse(Path source, String sourceHash, ParserPreset preset) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(preset, "preset");
        try {
            var process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            process.getOutputStream()
                    .write(requestJson(source, sourceHash, preset).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.warn("local model worker timed out command={} preset={}", command, preset.id());
                return Optional.empty();
            }
            var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            var root = MAPPER.readTree(extractJsonObject(stdout));
            if (!root.path("ok").asBoolean(false)) {
                LOG.warn(
                        "local model worker failed command={} preset={} message={} stderr={}",
                        command,
                        preset.id(),
                        root.path("message").asText("unknown"),
                        stderr.strip());
                return Optional.empty();
            }
            return Optional.of(TrustDocumentJson.fromJsonFull(MAPPER.writeValueAsString(root.path("document"))));
        } catch (IOException e) {
            LOG.warn(
                    "local model worker unavailable command={} preset={} message={}",
                    command,
                    preset.id(),
                    e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("local model worker interrupted command={} preset={}", command, preset.id());
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn(
                    "local model worker returned unusable output command={} preset={} message={}",
                    command,
                    preset.id(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    private static String requestJson(Path source, String sourceHash, ParserPreset preset) throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("version", 1);
        request.put("preset", preset.id());
        request.put("sourcePath", source.toAbsolutePath().toString());
        request.put("sourceHash", sourceHash);
        request.put("sourceFilename", source.getFileName().toString());
        var models = ModelManifestResolver.requiredArtifacts(preset);
        var cacheDir = modelCacheDirectory();
        var cacheReport = ModelCacheVerifier.verify(
                cacheDir, models.stream().map(ModelManifestArtifact::descriptor).toList());
        request.put("modelCacheDirectory", cacheDir.toAbsolutePath().toString());
        request.putArray("models")
                .addAll(models.stream()
                        .map(artifact -> modelJson(cacheDir, artifact, cacheReport))
                        .toList());
        request.put("bytesBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(source)));
        return MAPPER.writeValueAsString(request);
    }

    private static ObjectNode modelJson(
            Path cacheDir, ModelManifestArtifact manifestArtifact, ModelCacheReport cacheReport) {
        var model = manifestArtifact.descriptor();
        var artifact = cacheReport.artifacts().stream()
                .filter(item -> item.descriptor().identity().equals(model.identity()))
                .findFirst()
                .orElseThrow();
        var item = MAPPER.createObjectNode()
                .put("name", model.name())
                .put("version", model.version())
                .put("sha256", model.sha256())
                .put("sizeBytes", model.sizeBytes())
                .put("required", model.required())
                .put(
                        "cachePath",
                        cacheDir.resolve(model.cacheFilename()).toAbsolutePath().toString())
                .put("cacheStatus", artifact.status().name())
                .put("actualSha256", artifact.actualSha256())
                .put("actualSizeBytes", artifact.actualSizeBytes());
        putRuntimeHints(item, manifestArtifact.runtime());
        return item;
    }

    private static void putRuntimeHints(ObjectNode item, ModelRuntimeHints runtime) {
        if (!runtime.hasAny()) {
            return;
        }
        item.put("task", runtime.task());
        item.put("backend", runtime.backend());
        item.put("format", runtime.format());
        item.put("precision", runtime.precision());
        item.put("license", runtime.license());
    }

    static Optional<String> configuredCommand() {
        return setting("doctruth.model.command")
                .or(() -> environment("DOCTRUTH_MODEL_COMMAND"))
                .or(() -> environment("LOCAL_MODEL_COMMAND"));
    }

    private static Path modelCacheDirectory() {
        return setting("doctruth.model.cache")
                .or(() -> environment("DOCTRUTH_MODEL_CACHE"))
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".cache", "doctruth", "models"));
    }

    static String extractJsonObject(String stdout) {
        var trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty model worker stdout");
        }
        int start = trimmed.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("model worker stdout did not contain JSON");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (escaping) {
                escaping = false;
            } else if (ch == '\\') {
                escaping = inString;
            } else if (ch == '"') {
                inString = !inString;
            } else if (!inString && ch == '{') {
                depth++;
            } else if (!inString && ch == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("model worker stdout JSON was incomplete");
    }

    private static Optional<String> setting(String key) {
        return Optional.ofNullable(System.getProperty(key)).filter(value -> !value.isBlank());
    }

    private static Optional<String> environment(String key) {
        return Optional.ofNullable(System.getenv(key)).filter(value -> !value.isBlank());
    }

    private static String requireNonBlank(String value) {
        Objects.requireNonNull(value, "command");
        if (value.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        return value;
    }
}
