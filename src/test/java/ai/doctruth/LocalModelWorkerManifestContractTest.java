package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalModelWorkerManifestContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("local model worker request can use manifest-defined READY cache artifacts")
    void localModelWorkerRequestCanUseManifestDefinedReadyCacheArtifacts() throws Exception {
        var pdf = writePdf("Manifest model worker source.");
        var cache = tempDir.resolve("model-cache");
        Files.createDirectories(cache);
        var modelBytes = "local manifest model".getBytes(StandardCharsets.UTF_8);
        Files.write(cache.resolve("slanet-plus-local-test.bin"), modelBytes);
        var sha256 = "sha256:" + sha256Hex(modelBytes);
        var manifest = writeManifest(sha256, modelBytes.length);
        var worker = fakeModelWorker(cache, sha256, modelBytes.length);

        withSystemProperties(
                Map.of(
                        "doctruth.model.cache", cache.toString(),
                        "doctruth.model.manifest", manifest.toString()),
                () -> {
                    var doc = new LocalModelWorker(worker.toString())
                            .parse(pdf, "sha256:" + sha256Hex(Files.readAllBytes(pdf)), ParserPreset.TABLE_LITE)
                            .orElseThrow();

                    assertThat(doc.parserRun().backend()).isEqualTo("pdfbox+model-worker");
                    assertThat(doc.parserRun().models()).containsExactly("slanet-plus:local-test");
                });
    }

    @Test
    @DisplayName("model manifest resolver falls back to preset policy when manifest is absent or not preset-shaped")
    void manifestResolverFallsBackWhenManifestIsAbsentOrNotPresetShaped() throws Exception {
        var missingManifest = tempDir.resolve("missing-models.json");
        var nonPresetManifest = tempDir.resolve("non-preset-models.json");
        Files.writeString(nonPresetManifest, "{\"presets\":{\"table-lite\":{\"name\":\"not-an-array\"}}}");

        withSystemProperties(Map.of("doctruth.model.manifest", missingManifest.toString()), () -> assertThat(
                        ModelManifestResolver.requiredArtifacts(ParserPreset.TABLE_LITE))
                .extracting(artifact -> artifact.descriptor().identity())
                .containsExactlyElementsOf(ParserPreset.TABLE_LITE.runtimePolicy().requiredModels().stream()
                        .map(ModelDescriptor::identity)
                        .toList()));

        withSystemProperties(Map.of("doctruth.model.manifest", nonPresetManifest.toString()), () -> assertThat(
                        ModelManifestResolver.requiredArtifacts(ParserPreset.TABLE_LITE))
                .extracting(artifact -> artifact.descriptor().identity())
                .containsExactlyElementsOf(ParserPreset.TABLE_LITE.runtimePolicy().requiredModels().stream()
                        .map(ModelDescriptor::identity)
                        .toList()));
    }

    @Test
    @DisplayName("model manifest resolver rejects artifacts missing required identity fields")
    void manifestResolverRejectsMissingRequiredFields() throws Exception {
        var manifest = tempDir.resolve("bad-models.json");
        Files.writeString(manifest, """
                {
                  "presets": {
                    "table-lite": [
                      {"name": "slanet-plus", "version": "", "sha256": "sha256:abc", "sizeBytes": 1}
                    ]
                  }
                }
                """, StandardCharsets.UTF_8);

        withSystemProperties(Map.of("doctruth.model.manifest", manifest.toString()), () -> assertThatThrownBy(
                        () -> ModelManifestResolver.requiredArtifacts(ParserPreset.TABLE_LITE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version"));
    }

    @Test
    @DisplayName("model manifest resolver preserves optional runtime hints and source URI")
    void manifestResolverPreservesOptionalRuntimeHintsAndSource() throws Exception {
        var manifest = writeManifest("sha256:" + "a".repeat(64), 10);

        withSystemProperties(Map.of("doctruth.model.manifest", manifest.toString()), () -> {
            var artifact = ModelManifestResolver.requiredArtifacts(ParserPreset.TABLE_LITE)
                    .getFirst();

            assertThat(artifact.descriptor().identity()).isEqualTo("slanet-plus:local-test");
            assertThat(artifact.runtime().task()).contains("table-structure");
            assertThat(artifact.runtime().backend()).contains("onnxruntime");
            assertThat(artifact.runtime().format()).contains("onnx");
            assertThat(artifact.runtime().precision()).contains("int8");
            assertThat(artifact.runtime().license()).contains("apache-2.0");
            assertThat(artifact.source()).isEmpty();
        });
    }

    private Path writeManifest(String sha256, int sizeBytes) throws IOException {
        var manifest = tempDir.resolve("models.json");
        Files.writeString(manifest, """
                {
                  "presets": {
                    "table-lite": [
                      {
                        "name": "slanet-plus",
                        "version": "local-test",
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
                """.formatted(sha256, sizeBytes), StandardCharsets.UTF_8);
        return manifest;
    }

    private Path fakeModelWorker(Path cache, String sha256, int sizeBytes) throws IOException {
        var worker = tempDir.resolve("fake-model-worker");
        Files.writeString(
                worker,
                """
                #!/usr/bin/env python3
                import json
                import pathlib
                import sys

                request = json.loads(sys.stdin.read())
                model = request["models"][0]
                assert request["preset"] == "table-lite"
                assert pathlib.Path(request["modelCacheDirectory"]).resolve() == pathlib.Path(%s).resolve()
                assert model["name"] == "slanet-plus"
                assert model["version"] == "local-test"
                assert model["sha256"] == %s
                assert model["task"] == "table-structure"
                assert model["backend"] == "onnxruntime"
                assert model["format"] == "onnx"
                assert model["precision"] == "int8"
                assert model["license"] == "apache-2.0"
                assert model["cachePath"].endswith("slanet-plus-local-test.bin")
                assert model["cacheStatus"] == "READY"
                assert model["actualSha256"] == %s
                assert model["actualSizeBytes"] == %d
                source = pathlib.Path(request["sourcePath"]).name
                payload = {
                    "ok": True,
                    "document": {
                        "docId": request["sourceHash"],
                        "source": {
                            "sourceFilename": source,
                            "sourceHash": request["sourceHash"],
                            "metadata": {"sourceFilename": source, "pageCount": 1},
                        },
                        "body": {
                            "pages": [{
                                "pageNumber": 1,
                                "width": 612,
                                "height": 792,
                                "textLayerAvailable": True,
                                "imageHash": "sha256:model-page"
                            }],
                            "units": [],
                            "tables": [],
                        },
                        "parserRun": {
                            "parserVersion": "1.0.0",
                            "preset": "table-lite",
                            "backend": "pdfbox+model-worker",
                            "models": ["slanet-plus:local-test"],
                            "warnings": [],
                        },
                        "auditGradeStatus": "UNKNOWN",
                    }
                }
                print(json.dumps(payload))
                """.formatted(pythonLiteral(cache.toString()), pythonLiteral(sha256), pythonLiteral(sha256), sizeBytes),
                StandardCharsets.UTF_8);
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        return worker;
    }

    private Path writePdf(String text) throws Exception {
        var path = tempDir.resolve("manifest-model-worker.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private static void withSystemProperties(Map<String, String> values, ThrowingRunnable runnable) throws Exception {
        var previous = new java.util.HashMap<String, String>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            runnable.run();
        } finally {
            values.keySet().forEach(key -> {
                var old = previous.get(key);
                if (old == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, old);
                }
            });
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String pythonLiteral(String value) {
        return "'''" + value.replace("\\", "\\\\").replace("'''", "'\"'\"'") + "'''";
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
