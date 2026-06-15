package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliDoctorCompletionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void doctorReportsRuntimeAndConfigurationReadiness() throws Exception {
        Path runtime = fakeRustRuntime();
        var cli = cli(Map.of("OPENAI_API_KEY", "test-key", "DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int code = cli.run(new String[] {"doctor"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("DocTruth doctor")
                .contains("java:")
                .contains("parser backend: sidecar ok")
                .contains("model cache:")
                .contains("ocr worker:")
                .contains("memory max:")
                .contains("project:")
                .contains("OPENAI_API_KEY: set")
                .contains("ready:");
    }

    @Test
    void doctorJsonReportsMachineReadableReadiness() throws Exception {
        var cli = cli(Map.of());

        int code = withSystemProperty(
                "doctruth.runtime.disableSourceDiscovery", "true", () -> cli.run(new String[] {"doctor", "--json"}));

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("java").path("version").asText()).isNotBlank();
        assertThat(tree.path("parser").path("backend").asText()).isEqualTo("sidecar");
        assertThat(tree.path("parser").path("available").asBoolean()).isFalse();
        assertThat(tree.path("ocr").path("available").asBoolean()).isFalse();
        assertThat(tree.path("ocr").path("engine").asText()).isEqualTo("mnn");
        assertThat(tree.path("models").path("cacheDirectory").asText()).isNotBlank();
        assertThat(tree.path("models").path("requiredModels").asInt()).isZero();
        assertThat(tree.path("memory").path("maxMb").asLong()).isPositive();
        assertThat(tree.path("env").path("OPENAI_API_KEY").asBoolean()).isFalse();
    }

    @Test
    void doctorJsonReportsConfiguredRustRuntimeAsDefaultParser() throws Exception {
        Path runtime = fakeRustRuntime();
        var cli = cli(Map.of("DOCTRUTH_RUNTIME_COMMAND", runtime.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var parser = MAPPER.readTree(cli.out()).path("parser");
        assertThat(parser.path("backend").asText()).isEqualTo("sidecar");
        assertThat(parser.path("available").asBoolean()).isTrue();
        assertThat(parser.path("outputProfiles").toString()).contains("json_full", "parse_trace");
        assertThat(parser.path("runtimeDoctor").path("capabilities").path("native_text").path("available").asBoolean())
                .isTrue();
        assertThat(parser.path("runtimeDoctor").path("capabilities").path("layout").path("available").asBoolean())
                .isFalse();
        assertThat(parser.path("runtimeDoctor").path("models").path("presets").path("lite").path("allReady").asBoolean())
                .isTrue();
        assertThat(parser.path("runtimeDoctor").path("models").path("worker").path("ready").asBoolean()).isFalse();
    }

    @Test
    void doctorReportsConfiguredOcrWorkerReadiness() throws Exception {
        Path worker = tempDir.resolve("fake-ocr-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":true,"engine":"mnn","message":"ready"}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of(
                "DOCTRUTH_OCR_COMMAND", worker.toString(),
                "DOCTRUTH_OCR_ENGINE", "mnn",
                "DOCTRUTH_OCR_FALLBACK_ENGINE", "onnxruntime",
                "DOCTRUTH_OCR_TIMEOUT_MS", "1234"));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("ocr").path("available").asBoolean()).isTrue();
        assertThat(tree.path("ocr").path("ready").asBoolean()).isTrue();
        assertThat(tree.path("ocr").path("statusCode").asText()).isEqualTo("ready");
        assertThat(tree.path("ocr").path("command").asText()).isEqualTo(worker.toString());
        assertThat(tree.path("ocr").path("engine").asText()).isEqualTo("mnn");
        assertThat(tree.path("ocr").path("fallbackEngine").asText()).isEqualTo("onnxruntime");
        assertThat(tree.path("ocr").path("timeoutMs").asLong()).isEqualTo(1234);
    }

    @Test
    void doctorSeparatesExecutableOcrWorkerFromRuntimeReadyWorker() throws Exception {
        Path worker = tempDir.resolve("broken-rapidocr-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":false,"code":"rapidocr_unavailable","engine":"mnn","message":"numpy ABI mismatch"}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("DOCTRUTH_OCR_COMMAND", worker.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("ocr").path("available").asBoolean()).isTrue();
        assertThat(tree.path("ocr").path("ready").asBoolean()).isFalse();
        assertThat(tree.path("ocr").path("statusCode").asText()).isEqualTo("rapidocr_unavailable");
        assertThat(tree.path("ocr").path("message").asText()).contains("numpy ABI mismatch");
    }

    @Test
    void doctorReportsOcrDisabledWithoutTryingWorkerDiscovery() throws Exception {
        var cli = cli(Map.of(
                "DOCTRUTH_OCR_ENABLED", "false",
                "DOCTRUTH_OCR_COMMAND", tempDir.resolve("missing-worker").toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var ocr = MAPPER.readTree(cli.out()).path("ocr");
        assertThat(ocr.path("disabled").asBoolean()).isTrue();
        assertThat(ocr.path("available").asBoolean()).isFalse();
        assertThat(ocr.path("statusCode").asText()).isEqualTo("missing");
    }

    @Test
    void doctorReportsOcrUnreadableDoctorOutput() throws Exception {
        Path worker = tempDir.resolve("bad-json-ocr-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf 'not-json'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("DOCTRUTH_OCR_COMMAND", worker.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var ocr = MAPPER.readTree(cli.out()).path("ocr");
        assertThat(ocr.path("available").asBoolean()).isTrue();
        assertThat(ocr.path("ready").asBoolean()).isFalse();
        assertThat(ocr.path("statusCode").asText()).isEqualTo("worker_doctor_unavailable");
    }

    @Test
    void doctorDiscoversDocTruthRapidOcrWorkerOnPath() throws Exception {
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Path worker = bin.resolve("doctruth-rapidocr-mnn-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":true,"engine":"mnn","message":"ready"}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("PATH", bin.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("ocr").path("available").asBoolean()).isTrue();
        assertThat(tree.path("ocr").path("ready").asBoolean()).isTrue();
        assertThat(tree.path("ocr").path("command").asText()).isEqualTo(worker.toString());
        assertThat(tree.path("ocr").path("engine").asText()).isEqualTo("mnn");
    }

    @Test
    void doctorReportsOcrWorkerTimeoutAndEmptyOutput() throws Exception {
        Path slowWorker = tempDir.resolve("slow-ocr-worker");
        Files.writeString(
                slowWorker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  sleep 1
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(slowWorker.toFile().setExecutable(true)).isTrue();
        var timeoutCli = cli(Map.of(
                "LOCAL_OCR_COMMAND", slowWorker.toString(),
                "LOCAL_OCR_TIMEOUT_MS", "1"));

        int timeoutCode = timeoutCli.run(new String[] {"doctor", "--json"});

        assertThat(timeoutCode).isZero();
        var timeoutOcr = MAPPER.readTree(timeoutCli.out()).path("ocr");
        assertThat(timeoutOcr.path("available").asBoolean()).isTrue();
        assertThat(timeoutOcr.path("ready").asBoolean()).isFalse();
        assertThat(timeoutOcr.path("statusCode").asText()).isEqualTo("worker_doctor_timeout");

        Path emptyWorker = tempDir.resolve("empty-ocr-worker");
        Files.writeString(
                emptyWorker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  echo 'ocr not initialized' >&2
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(emptyWorker.toFile().setExecutable(true)).isTrue();
        var emptyCli = cli(Map.of("LOCAL_OCR_COMMAND", emptyWorker.toString()));

        int emptyCode = emptyCli.run(new String[] {"doctor", "--json"});

        assertThat(emptyCode).isZero();
        var emptyOcr = MAPPER.readTree(emptyCli.out()).path("ocr");
        assertThat(emptyOcr.path("statusCode").asText()).isEqualTo("worker_doctor_empty");
        assertThat(emptyOcr.path("message").asText()).contains("ocr not initialized");
    }

    @Test
    void doctorModelsReportsLocalModelCacheWithoutDownloads() {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"doctor", "models"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("DocTruth model doctor")
                .contains("model cache:")
                .contains("required models: 0")
                .contains("network access required: no");
    }

    @Test
    void doctorJsonReportsConfiguredModelWorkerReadiness() throws Exception {
        Path worker = tempDir.resolve("fake-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":true,"engine":"onnxruntime","message":"model worker ready","loadedModels":["slanet-plus:v1"],"rssMb":128,"peakMemoryMb":512}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of(
                "DOCTRUTH_MODEL_COMMAND", worker.toString(),
                "DOCTRUTH_MODEL_TIMEOUT_MS", "2345"));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        var modelWorker = tree.path("models").path("worker");
        assertThat(modelWorker.path("command").asText()).isEqualTo(worker.toString());
        assertThat(modelWorker.path("available").asBoolean()).isTrue();
        assertThat(modelWorker.path("ready").asBoolean()).isTrue();
        assertThat(modelWorker.path("statusCode").asText()).isEqualTo("ready");
        assertThat(modelWorker.path("message").asText()).isEqualTo("model worker ready");
        assertThat(modelWorker.path("timeoutMs").asLong()).isEqualTo(2345);
        assertThat(modelWorker.path("rssMb").asLong()).isEqualTo(128);
        assertThat(modelWorker.path("peakMemoryMb").asLong()).isEqualTo(512);
        assertThat(modelWorker.path("loadedModels")).hasSize(1);
        assertThat(modelWorker.path("loadedModels").get(0).asText()).isEqualTo("slanet-plus:v1");
    }

    @Test
    void doctorDiscoversModelWorkerOnPathAndSupportsLegacyEnvAlias() throws Exception {
        Path bin = tempDir.resolve("model-bin");
        Files.createDirectories(bin);
        Path worker = bin.resolve("doctruth-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":true,"message":"path worker ready","loadedModels":[],"rssMb":64,"peakMemoryMb":128}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of(
                "LOCAL_MODEL_COMMAND", "doctruth-model-worker",
                "PATH", bin.toString(),
                "LOCAL_MODEL_TIMEOUT_MS", "not-a-number"));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var modelWorker = MAPPER.readTree(cli.out()).path("models").path("worker");
        assertThat(modelWorker.path("command").asText()).isEqualTo(worker.toString());
        assertThat(modelWorker.path("ready").asBoolean()).isTrue();
        assertThat(modelWorker.path("message").asText()).isEqualTo("path worker ready");
        assertThat(modelWorker.path("timeoutMs").asLong()).isEqualTo(60_000);
    }

    @Test
    void doctorJsonReportsManifestModelArtifactsReadyFromLocalCache() throws Exception {
        Path cache = tempDir.resolve("model-cache");
        Files.createDirectories(cache);
        byte[] modelBytes = "ready local model".getBytes(StandardCharsets.UTF_8);
        String sha256 = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modelBytes));
        Files.write(cache.resolve("slanet-plus-local.bin"), modelBytes);
        Path manifest = writeModelManifest("slanet-plus", "local", sha256, modelBytes.length);
        var cli = cli(Map.of(
                "DOCTRUTH_MODEL_MANIFEST", manifest.toString(),
                "DOCTRUTH_MODEL_CACHE", cache.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var models = MAPPER.readTree(cli.out()).path("models");
        assertThat(models.path("requiredModels").asInt()).isEqualTo(1);
        assertThat(models.path("allReady").asBoolean()).isTrue();
        assertThat(models.path("artifacts")).hasSize(1);
        var artifact = models.path("artifacts").get(0);
        assertThat(artifact.path("identity").asText()).isEqualTo("slanet-plus:local");
        assertThat(artifact.path("status").asText()).isEqualTo("READY");
        assertThat(artifact.path("actualSha256").asText()).isEqualTo(sha256);
        assertThat(artifact.path("actualSizeBytes").asLong()).isEqualTo(modelBytes.length);
        assertThat(artifact.path("task").asText()).isEqualTo("table-structure");
        assertThat(artifact.path("backend").asText()).isEqualTo("onnxruntime");
        assertThat(artifact.path("format").asText()).isEqualTo("onnx");
        assertThat(artifact.path("precision").asText()).isEqualTo("int8");
        assertThat(artifact.path("license").asText()).isEqualTo("apache-2.0");
    }

    @Test
    void doctorJsonReportsMissingManifestModelArtifacts() throws Exception {
        Path cache = tempDir.resolve("empty-model-cache");
        Files.createDirectories(cache);
        String sha256 = "sha256:" + "a".repeat(64);
        Path manifest = writeModelManifest("slanet-plus", "missing", sha256, 1024);
        var cli = cli(Map.of(
                "DOCTRUTH_MODEL_MANIFEST", manifest.toString(),
                "DOCTRUTH_MODEL_CACHE", cache.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var models = MAPPER.readTree(cli.out()).path("models");
        assertThat(models.path("requiredModels").asInt()).isEqualTo(1);
        assertThat(models.path("allReady").asBoolean()).isFalse();
        assertThat(models.path("artifacts")).hasSize(1);
        assertThat(models.path("artifacts").get(0).path("status").asText()).isEqualTo("MISSING");
    }

    @Test
    void doctorSeparatesExecutableModelWorkerFromRuntimeReadyWorker() throws Exception {
        Path worker = tempDir.resolve("broken-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":false,"code":"model_runtime_unavailable","message":"onnxruntime missing"}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("DOCTRUTH_MODEL_COMMAND", worker.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var modelWorker = MAPPER.readTree(cli.out()).path("models").path("worker");
        assertThat(modelWorker.path("available").asBoolean()).isTrue();
        assertThat(modelWorker.path("ready").asBoolean()).isFalse();
        assertThat(modelWorker.path("statusCode").asText()).isEqualTo("model_runtime_unavailable");
        assertThat(modelWorker.path("message").asText()).contains("onnxruntime missing");
        assertThat(modelWorker.path("rssMb").asLong()).isZero();
        assertThat(modelWorker.path("peakMemoryMb").asLong()).isZero();
    }

    @Test
    void doctorReportsModelWorkerProtocolErrorsAndClampsNegativeResources() throws Exception {
        Path worker = tempDir.resolve("bad-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"ok":true,"message":"ready","loadedModels":["m"],"rssMb":-9,"peakMemoryMb":-1}'
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("DOCTRUTH_MODEL_COMMAND", worker.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var workerNode = MAPPER.readTree(cli.out()).path("models").path("worker");
        assertThat(workerNode.path("ready").asBoolean()).isTrue();
        assertThat(workerNode.path("rssMb").asLong()).isZero();
        assertThat(workerNode.path("peakMemoryMb").asLong()).isZero();
        assertThat(workerNode.path("loadedModels").get(0).asText()).isEqualTo("m");
    }

    @Test
    void doctorReportsModelWorkerEmptyStdoutAsNotReady() throws Exception {
        Path worker = tempDir.resolve("empty-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  echo 'worker not initialized' >&2
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var cli = cli(Map.of("DOCTRUTH_MODEL_COMMAND", worker.toString()));

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var workerNode = MAPPER.readTree(cli.out()).path("models").path("worker");
        assertThat(workerNode.path("ready").asBoolean()).isFalse();
        assertThat(workerNode.path("statusCode").asText()).isEqualTo("worker_doctor_empty");
        assertThat(workerNode.path("message").asText()).contains("worker not initialized");
    }

    @Test
    void doctorReportsModelWorkerTimeoutAndMissingPath() throws Exception {
        Path slowWorker = tempDir.resolve("slow-model-worker");
        Files.writeString(
                slowWorker,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  sleep 1
                  exit 0
                fi
                exit 0
                """,
                StandardCharsets.UTF_8);
        assertThat(slowWorker.toFile().setExecutable(true)).isTrue();
        var timeoutCli = cli(Map.of(
                "DOCTRUTH_MODEL_COMMAND", slowWorker.toString(),
                "DOCTRUTH_MODEL_TIMEOUT_MS", "1"));

        int timeoutCode = timeoutCli.run(new String[] {"doctor", "--json"});

        assertThat(timeoutCode).isZero();
        var timeoutWorker = MAPPER.readTree(timeoutCli.out()).path("models").path("worker");
        assertThat(timeoutWorker.path("available").asBoolean()).isTrue();
        assertThat(timeoutWorker.path("ready").asBoolean()).isFalse();
        assertThat(timeoutWorker.path("statusCode").asText()).isEqualTo("worker_doctor_timeout");

        var missingPathCli = cli(Map.of(
                "DOCTRUTH_MODEL_COMMAND", "missing-model-worker",
                "PATH", ""));

        int missingPathCode = missingPathCli.run(new String[] {"doctor", "--json"});

        assertThat(missingPathCode).isZero();
        var missingWorker = MAPPER.readTree(missingPathCli.out()).path("models").path("worker");
        assertThat(missingWorker.path("available").asBoolean()).isFalse();
        assertThat(missingWorker.path("statusCode").asText()).isEqualTo("missing");
    }

    @Test
    void completionPrintsShellScript() {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"completion", "bash"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("_doctruth")
                .contains("doctor")
                .contains("mcp")
                .contains("benchmark-corpus")
                .contains("verify-audit")
                .contains("verify-source-map")
                .contains("verify-benchmark-report")
                .contains("completion");
    }

    @Test
    void completionSupportsZshAndFish() {
        var zsh = cli(Map.of());
        var fish = cli(Map.of());

        assertThat(zsh.run(new String[] {"completion", "zsh"})).isZero();
        assertThat(fish.run(new String[] {"completion", "fish"})).isZero();
        assertThat(zsh.out()).contains("#compdef doctruth").contains("compadd");
        assertThat(fish.out()).contains("complete -c doctruth");
    }

    @Test
    void completionRejectsMissingShell() {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"completion"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("doctruth completion <bash|zsh|fish>");
    }

    @Test
    void completionRejectsUnsupportedShell() {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"completion", "powershell"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("supported shells: bash, zsh, fish");
    }

    private static TestCli cli(Map<String, String> env) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                spec -> "{}",
                Providers::create);
        return new TestCli(cli, out, err);
    }

    private Path writeModelManifest(String name, String version, String sha256, long sizeBytes) throws Exception {
        Path manifest = tempDir.resolve("model-manifest-" + version + ".json");
        Files.writeString(
                manifest,
                """
                {
                  "presets": {
                    "table-lite": [
                      {
                        "name": "%s",
                        "version": "%s",
                        "sha256": "%s",
                        "sizeBytes": %d,
                        "required": true,
                        "task": "table-structure",
                        "backend": "onnxruntime",
                        "format": "onnx",
                        "precision": "int8",
                        "license": "apache-2.0"
                      }
                    ]
                  }
                }
                """
                        .formatted(name, version, sha256, sizeBytes),
                StandardCharsets.UTF_8);
        return manifest;
    }

    private static int withSystemProperty(String key, String value, ThrowingIntSupplier supplier) throws Exception {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return supplier.getAsInt();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }

    private Path fakeRustRuntime() throws Exception {
        Path runtime = tempDir.resolve("doctruth-runtime");
        Files.writeString(
                runtime,
                """
                #!/usr/bin/env sh
                if [ "$1" = "--doctor" ]; then
                  printf '{"runtime":"doctruth-runtime","capabilities":{"parse_pdf":true,"native_text":{"available":true,"backend":"pdf_oxide"},"layout":{"available":false},"tables":{"available":false},"ocr":{"available":false}},"models":{"worker":{"configured":false,"available":false,"ready":false},"presets":{"lite":{"allReady":true}}}}'
                  exit 0
                fi
                cat >/dev/null
                printf '{}'
                """,
                StandardCharsets.UTF_8);
        assertThat(runtime.toFile().setExecutable(true)).isTrue();
        return runtime;
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
