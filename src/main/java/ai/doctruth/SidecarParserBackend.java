package ai.doctruth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Parser backend that delegates PDF parsing to a local runtime sidecar process.
 *
 * @since 1.0.0
 */
public final class SidecarParserBackend implements ParserBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path runtime;
    private final Duration timeout;

    public SidecarParserBackend(Path runtime) {
        this(runtime, DEFAULT_TIMEOUT);
    }

    public SidecarParserBackend(Path runtime, Duration timeout) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalArgumentException("runtime must be a regular file");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public TrustDocument parse(ParserRequest request) throws ParseException {
        Objects.requireNonNull(request, "request");
        var process = startProcess(request);
        try {
            process.getOutputStream().write(requestJson(request).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw parseException("SIDECAR_RUNTIME_TIMEOUT", "sidecar parser timed out", request, null);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw parseException(
                        "SIDECAR_RUNTIME_FAILED",
                        "sidecar parser exited with code " + process.exitValue() + ": " + stderr.strip(),
                        request,
                        null);
            }
            return TrustDocumentJson.fromJsonFull(stdout);
        } catch (ParseException e) {
            throw e;
        } catch (IOException e) {
            throw parseException("SIDECAR_IO_FAILED", "sidecar parser I/O failed: " + e.getMessage(), request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw parseException("SIDECAR_INTERRUPTED", "sidecar parser was interrupted", request, e);
        } catch (RuntimeException e) {
            throw parseException("SIDECAR_INVALID_RESPONSE", "sidecar parser returned invalid JSON", request, e);
        }
    }

    @Override
    public ParserCapabilities capabilities() {
        return new ParserCapabilities(
                "sidecar",
                true,
                true,
                false,
                List.of(
                        "json_full",
                        "json_evidence",
                        "markdown_clean",
                        "plain_text",
                        "compact_llm",
                        "html_review",
                        "content_blocks",
                        "parse_trace"));
    }

    @Override
    public ParserHealth doctor() {
        boolean executable = Files.isExecutable(runtime);
        var warnings = executable
                ? List.<ParserWarning>of()
                : List.of(new ParserWarning(
                        "sidecar_not_executable", ParserWarningSeverity.SEVERE, "sidecar runtime is not executable"));
        return new ParserHealth("sidecar", executable, warnings);
    }

    private Process startProcess(ParserRequest request) throws ParseException {
        try {
            var process = new ProcessBuilder(runtime.toString());
            configureChildEnvironment(process.environment(), request);
            return process.start();
        } catch (IOException e) {
            throw parseException(
                    "SIDECAR_START_FAILED", "failed to start sidecar parser: " + e.getMessage(), request, e);
        }
    }

    private static void configureChildEnvironment(Map<String, String> env, ParserRequest request) {
        configuredRuntimeWorkerCommand(request)
                .ifPresent(command -> putIfAbsent(env, "DOCTRUTH_RUNTIME_MODEL_COMMAND", command));
        LocalModelWorker.configuredCommand().ifPresent(command -> putIfAbsent(env, "DOCTRUTH_MODEL_COMMAND", command));
        setting("doctruth.model.cache").ifPresent(value -> putIfAbsent(env, "DOCTRUTH_MODEL_CACHE", value));
        setting("doctruth.model.manifest").ifPresent(value -> putIfAbsent(env, "DOCTRUTH_MODEL_MANIFEST", value));
    }

    private static java.util.Optional<String> configuredRuntimeWorkerCommand(ParserRequest request) {
        if ("ocr".equals(request.parserRun().preset())) {
            return setting("doctruth.ocr.command")
                    .or(() -> environment("DOCTRUTH_OCR_COMMAND"))
                    .or(() -> environment("LOCAL_OCR_COMMAND"))
                    .or(LocalModelWorker::configuredCommand);
        }
        return LocalModelWorker.configuredCommand();
    }

    private static void putIfAbsent(Map<String, String> env, String key, String value) {
        if (!env.containsKey(key) || env.get(key).isBlank()) {
            env.put(key, value);
        }
    }

    private static java.util.Optional<String> setting(String key) {
        return java.util.Optional.ofNullable(System.getProperty(key)).filter(value -> !value.isBlank());
    }

    private static java.util.Optional<String> environment(String key) {
        return java.util.Optional.ofNullable(System.getenv(key)).filter(value -> !value.isBlank());
    }

    private static String requestJson(ParserRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("command", "parse_pdf");
        root.put("source_path", request.sourcePath().toString());
        root.put("source_hash", request.sourceHash());
        root.put("preset", request.parserRun().preset());
        root.put("offline_mode", request.offlineMode());
        root.put("allow_model_downloads", request.allowModelDownloads());
        return root.toString();
    }

    private static ParseException parseException(String code, String message, ParserRequest request, Throwable cause) {
        return new ParseException(code, message, request.sourcePath().toString(), OptionalInt.empty(), cause);
    }
}
