package ai.doctruth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalOcrWorkerEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTradeBotCompatibleWorkerSuccessJson() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Scanned resume text","averageConfidence":0.88,"pages":[{"page":1,"text":"Scanned resume text","confidence":0.88}],"warnings":[]}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), 3);

        assertThat(result.text()).isEqualTo("Scanned resume text");
        assertThat(result.confidence()).isEqualTo(0.88);
        assertThat(result.pageNumber()).isEqualTo(3);
    }

    @Test
    void parsesWorkerPageRegionsIntoOcrResult() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Scanned resume text","averageConfidence":0.91,"pages":[{"page":1,"text":"Scanned resume text","confidence":0.91,"regions":[{"text":"Scanned","bbox":{"x":10,"y":20,"width":80,"height":18},"confidence":0.94},{"text":"resume","box":[100,20,70,18],"confidence":0.88}]}],"warnings":[]}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.regions()).hasSize(2);
        assertThat(result.regions().get(0).text()).isEqualTo("Scanned");
        assertThat(result.regions().get(0).box()).isEqualTo(new OcrBox(10, 20, 80, 18));
        assertThat(result.regions().get(0).confidence()).isEqualTo(0.94);
        assertThat(result.regions().get(1).text()).isEqualTo("resume");
        assertThat(result.regions().get(1).box()).isEqualTo(new OcrBox(100, 20, 70, 18));
        assertThat(result.regions().get(1).confidence()).isEqualTo(0.88);
    }

    @Test
    void extractsJsonWhenNativeRuntimeLogsAroundPayload() throws Exception {
        Path worker = fakeWorker("""
                MNN backend initialized
                {"ok":true,"engine":"mnn","text":"","averageConfidence":null,"pages":[{"page":1,"text":"Recovered from page","confidence":0.74}],"warnings":["hot cache"]}
                trailing native log
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.text()).isEqualTo("Recovered from page");
        assertThat(result.confidence()).isEqualTo(0.74);
    }

    @Test
    void joinsMultiplePageTextsAndAveragesPageConfidenceWhenSummaryMissing() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"","pages":[{"page":1,"text":"First OCR page","confidence":0.8},{"page":2,"text":"Second OCR page","confidence":0.6}],"warnings":[]}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.text()).isEqualTo("First OCR page\n\nSecond OCR page");
        assertThat(result.confidence()).isEqualTo(0.7);
    }

    @Test
    void returnsEmptyResultWhenWorkerFails() throws Exception {
        Path worker = fakeWorker("""
                {"ok":false,"code":"failed","engine":"mnn","message":"missing model"}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.text()).isEmpty();
        assertThat(result.confidence()).isZero();
    }

    @Test
    void returnsEmptyResultWhenWorkerIsMissingOrMalformed() throws Exception {
        var missing = new LocalOcrWorkerEngine(tempDir.resolve("missing-worker").toString(), "mnn", "onnxruntime", 5_000);
        assertThat(missing.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1).text()).isEmpty();

        Path malformed = fakeWorker("not json");
        var engine = new LocalOcrWorkerEngine(malformed.toString(), "mnn", "onnxruntime", 5_000);
        assertThat(engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1).text()).isEmpty();
    }

    @Test
    void returnsEmptyResultWhenWorkerReportsBlankSuccess() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"","averageConfidence":null,"pages":[],"warnings":[]}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.text()).isEmpty();
        assertThat(result.confidence()).isZero();
    }

    @Test
    void truncatesLongWorkerStderrOnFailure() throws Exception {
        Path worker = tempDir.resolve("noisy-ocr-worker");
        Files.writeString(
                worker,
                "#!/usr/bin/env bash\n"
                        + "python3 - <<'PY'\n"
                        + "import sys\n"
                        + "print('x' * 9000, file=sys.stderr)\n"
                        + "print('{\"ok\":false,\"code\":\"failed\",\"message\":\"failed\"}')\n"
                        + "PY\n");
        worker.toFile().setExecutable(true);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        assertThat(engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1).text()).isEmpty();
    }

    @Test
    void returnsEmptyResultWhenWorkerTimesOut() throws Exception {
        Path worker = tempDir.resolve("slow-ocr-worker");
        Files.writeString(worker, "#!/usr/bin/env bash\nsleep 2\n");
        worker.toFile().setExecutable(true);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 50);

        var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

        assertThat(result.text()).isEmpty();
    }

    @Test
    void constructorRejectsInvalidConfiguration() {
        assertThat(new LocalOcrWorkerEngine(tempDir.resolve("missing-worker").toString()))
                .isInstanceOf(LocalOcrWorkerEngine.class);
        assertThatThrownBy(() -> new LocalOcrWorkerEngine("", "mnn", "onnxruntime", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
        assertThatThrownBy(() -> new LocalOcrWorkerEngine("worker", "bad", "onnxruntime", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported OCR engine");
        assertThatThrownBy(() -> new LocalOcrWorkerEngine("worker", "mnn", "onnxruntime", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void ocrRejectsInvalidPageNumber() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"unused","averageConfidence":1.0,"pages":[],"warnings":[]}
                """);
        var engine = new LocalOcrWorkerEngine(worker.toString(), "mnn", "onnxruntime", 5_000);

        assertThatThrownBy(() -> engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageNumber");
    }

    @Test
    void defaultLocalUsesConfiguredWorkerCommand() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Configured OCR text","averageConfidence":0.93,"pages":[],"warnings":[]}
                """);
        withSystemProperty("doctruth.ocr.command", worker.toString(), () -> {
            var engine = OcrEngines.defaultLocal();

            var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

            assertThat(result.text()).isEqualTo("Configured OCR text");
            assertThat(result.confidence()).isEqualTo(0.93);
        });
    }

    @Test
    void defaultLocalAcceptsEngineTimeoutAndSameFallbackSettings() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"onnxruntime","text":"Configured ONNX OCR","averageConfidence":0.81,"pages":[],"warnings":[]}
                """);
        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.command", worker.toString(),
                        "doctruth.ocr.fallbackCommand", worker.toString(),
                        "doctruth.ocr.engine", "onnxruntime",
                        "doctruth.ocr.timeoutMs", "not-a-number"),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Configured ONNX OCR");
                    assertThat(result.confidence()).isEqualTo(0.81);
                });
    }

    @Test
    void defaultLocalAcceptsPositiveTimeoutAndBlankOptionalSettings() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Positive timeout OCR","averageConfidence":0.84,"pages":[],"warnings":[]}
                """);
        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.command", worker.toString(),
                        "doctruth.ocr.fallbackCommand", "   ",
                        "doctruth.ocr.timeoutMs", "2500"),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Positive timeout OCR");
                    assertThat(result.confidence()).isEqualTo(0.84);
                });
    }

    @Test
    void defaultLocalIgnoresNonPositiveTimeout() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Default timeout OCR","averageConfidence":0.85,"pages":[],"warnings":[]}
                """);
        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.command", worker.toString(),
                        "doctruth.ocr.timeoutMs", "-1"),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Default timeout OCR");
                    assertThat(result.confidence()).isEqualTo(0.85);
                });
    }

    @Test
    void defaultLocalFallsBackWhenNoWorkerCommandExists() {
        withSystemProperty("doctruth.ocr.command", tempDir.resolve("missing-worker").toString(), () -> {
            var engine = OcrEngines.defaultLocal();

            var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

            assertThat(result.text()).isEmpty();
            assertThat(result.confidence()).isZero();
        });
    }

    @Test
    void defaultLocalCanBeDisabledWithFalse() {
        withSystemProperty("doctruth.ocr.enabled", "false", () -> {
            var engine = OcrEngines.defaultLocal();

            var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

            assertThat(result.text()).isEmpty();
        });
    }

    @Test
    void defaultLocalCanBeDisabledWithZero() {
        withSystemProperty("doctruth.ocr.enabled", "0", () -> {
            var engine = OcrEngines.defaultLocal();

            var result = engine.ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

            assertThat(result.text()).isEmpty();
        });
    }

    @Test
    void defaultLocalTreatsEnabledTrueAsActive() throws Exception {
        Path worker = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Enabled OCR text","averageConfidence":0.86,"pages":[],"warnings":[]}
                """);
        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.enabled", "true",
                        "doctruth.ocr.command", worker.toString()),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Enabled OCR text");
                });
    }

    @Test
    void defaultLocalUsesFallbackCommandWhenPrimaryReturnsNoText() throws Exception {
        Path primary = fakeWorker("""
                {"ok":false,"code":"failed","engine":"mnn","message":"primary missing model"}
                """);
        Path fallback = tempDir.resolve("fallback-ocr-worker");
        Files.writeString(
                fallback,
                "#!/usr/bin/env bash\n"
                        + "python3 - <<'PY'\n"
                        + "import sys\n"
                        + "sys.stdin.read()\n"
                        + "print(" + pythonLiteral("""
                        {"ok":true,"engine":"onnxruntime","text":"Fallback OCR text","averageConfidence":0.82,"pages":[],"warnings":[]}
                        """) + ")\n"
                        + "PY\n");
        fallback.toFile().setExecutable(true);

        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.command", primary.toString(),
                        "doctruth.ocr.fallbackCommand", fallback.toString()),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Fallback OCR text");
                    assertThat(result.confidence()).isEqualTo(0.82);
                });
    }

    @Test
    void defaultLocalDoesNotUseFallbackWhenPrimaryReturnsText() throws Exception {
        Path primary = fakeWorker("""
                {"ok":true,"engine":"mnn","text":"Primary OCR text","averageConfidence":0.93,"pages":[],"warnings":[]}
                """);
        Path fallback = tempDir.resolve("unused-fallback-ocr-worker");
        Files.writeString(
                fallback,
                "#!/usr/bin/env bash\n"
                        + "echo 'fallback should not run' >&2\n"
                        + "exit 17\n");
        fallback.toFile().setExecutable(true);

        withSystemProperties(
                java.util.Map.of(
                        "doctruth.ocr.command", primary.toString(),
                        "doctruth.ocr.fallbackCommand", fallback.toString()),
                () -> {
                    var result = OcrEngines.defaultLocal()
                            .ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1);

                    assertThat(result.text()).isEqualTo("Primary OCR text");
                    assertThat(result.confidence()).isEqualTo(0.93);
                });
    }

    @Test
    void noopFactoryReturnsNoopEngine() {
        assertThat(OcrEngines.noop().ocr(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), 1).text()).isEmpty();
    }

    @Test
    void utilityConstructorIsNotInstantiable() throws Exception {
        var constructor = OcrEngines.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(AssertionError.class);
    }

    @Test
    void extractJsonObjectRejectsNonJsonStdout() {
        assertThatThrownBy(() -> LocalOcrWorkerEngine.extractJsonObject("native log only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
        assertThatThrownBy(() -> LocalOcrWorkerEngine.extractJsonObject(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> LocalOcrWorkerEngine.extractJsonObject("log {\"x\": \"unterminated\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    private Path fakeWorker(String stdout) throws Exception {
        Path worker = tempDir.resolve("fake-ocr-worker");
        Files.writeString(
                worker,
                "#!/usr/bin/env bash\n"
                        + "set -euo pipefail\n"
                        + "python3 - <<'PY'\n"
                        + "import sys\n"
                        + "sys.stdin.read()\n"
                        + "print(" + pythonLiteral(stdout) + ")\n"
                        + "PY\n");
        worker.toFile().setExecutable(true);
        return worker;
    }

    private static String pythonLiteral(String value) {
        return "'''"
                + value.replace("\\", "\\\\").replace("'''", "'\"'\"'")
                + "'''";
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) {
        withSystemProperties(java.util.Map.of(key, value), runnable);
    }

    private static void withSystemProperties(java.util.Map<String, String> values, ThrowingRunnable runnable) {
        var previous = new java.util.HashMap<String, String>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            runnable.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            values.keySet().forEach(key -> {
                String old = previous.get(key);
                if (old == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, old);
                }
            });
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
