package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelCacheCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void cacheWarmCopiesManifestLocalSourceAndVerifiesSha() throws Exception {
        var source = tempDir.resolve("slanet.onnx");
        Files.writeString(source, "tiny local model");
        var sha = "sha256:" + sha256Hex(source);
        var manifest = manifest("""
                "source": "%s",
                """.formatted(jsonEscape(source.toString())), sha);
        var cache = tempDir.resolve("cache");
        var cli = cli();

        int code = cli.run(new String[] {
                "cache", "warm", manifest.toString(), "--preset", "table-lite", "--cache", cache.toString(), "--json"
        });

        assertThat(code).isZero();
        assertThat(cache.resolve("slanet-plus-local.bin")).hasContent("tiny local model");
        JsonNode root = MAPPER.readTree(cli.out());
        assertThat(root.path("cacheDir").asText()).isEqualTo(cache.toString());
        assertThat(root.path("allReady").asBoolean()).isTrue();
        assertThat(root.path("artifacts").get(0).path("status").asText()).isEqualTo("READY");
        assertThat(root.path("artifacts").get(0).path("actualSha256").asText()).isEqualTo(sha);
        assertThat(root.path("artifacts").get(0).path("task").asText()).isEqualTo("table-structure");
        assertThat(root.path("artifacts").get(0).path("backend").asText()).isEqualTo("onnxruntime");
        assertThat(root.path("artifacts").get(0).path("format").asText()).isEqualTo("onnx");
        assertThat(root.path("artifacts").get(0).path("precision").asText()).isEqualTo("int8");
        assertThat(root.path("artifacts").get(0).path("license").asText()).isEqualTo("apache-2.0");
    }

    @Test
    void cacheWarmOfflineRejectsRemoteSourcesWithoutNetwork() throws Exception {
        var manifest = manifest("""
                "source": "https://models.example/slanet.onnx",
                """, "sha256:" + "0".repeat(64));
        var cli = cli();

        int code = cli.run(new String[] {
                "cache", "warm", manifest.toString(), "--preset", "table-lite", "--cache",
                tempDir.resolve("cache").toString(), "--offline"
        });

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("offline mode refuses remote model source");
    }

    @Test
    void cacheWarmDownloadsRemoteSourceAndVerifiesSha() throws Exception {
        byte[] bytes = "tiny remote model".getBytes(StandardCharsets.UTF_8);
        var sha = "sha256:" + sha256Hex(bytes);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slanet.onnx", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slanet.onnx";
            var manifest = manifest("""
                    "source": "%s",
                    """.formatted(url), sha);
            var cache = tempDir.resolve("remote-cache");
            var cli = cli();

            int code = cli.run(new String[] {
                    "cache", "warm", manifest.toString(), "--preset", "table-lite", "--cache", cache.toString(), "--json"
            });

            assertThat(code).isZero();
            assertThat(Files.readString(cache.resolve("slanet-plus-local.bin"))).isEqualTo("tiny remote model");
            JsonNode root = MAPPER.readTree(cli.out());
            assertThat(root.path("allReady").asBoolean()).isTrue();
            assertThat(root.path("artifacts").get(0).path("actualSha256").asText()).isEqualTo(sha);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheWarmUsesConfiguredDefaultCacheAndRejectsBadUsage() throws Exception {
        var source = tempDir.resolve("slanet.onnx");
        Files.writeString(source, "tiny default cache model");
        var sha = "sha256:" + sha256Hex(source);
        var manifest = manifest("""
                "source": "%s",
                """.formatted(jsonEscape(source.toString())), sha);
        var cache = tempDir.resolve("configured-cache");
        var cli = cli(Map.of("DOCTRUTH_MODEL_CACHE", cache.toString()));

        int code = cli.run(new String[] {"cache", "warm", manifest.toString(), "--preset", "table-lite"});

        assertThat(code).isZero();
        assertThat(cache.resolve("slanet-plus-local.bin")).hasContent("tiny default cache model");

        var missingPreset = cli();
        assertThat(missingPreset.run(new String[] {"cache", "warm", manifest.toString()})).isEqualTo(2);
        assertThat(missingPreset.err()).contains("--preset is required");

        var unknownOption = cli();
        assertThat(unknownOption.run(new String[] {
                    "cache", "warm", manifest.toString(), "--preset", "table-lite", "--wat"
                }))
                .isEqualTo(2);
        assertThat(unknownOption.err()).contains("unknown cache option");
    }

    @Test
    void cacheWarmRejectsMissingPresetAndMissingSource() throws Exception {
        var missingPresetManifest = tempDir.resolve("missing-preset-models.json");
        Files.writeString(missingPresetManifest, "{\"presets\":{}}");
        var cli = cli();

        int missingPresetCode = cli.run(new String[] {
            "cache", "warm", missingPresetManifest.toString(), "--preset", "table-lite", "--cache",
            tempDir.resolve("missing-preset-cache").toString()
        });

        assertThat(missingPresetCode).isEqualTo(2);
        assertThat(cli.err()).contains("model manifest preset not found");

        var noSourceManifest = manifest("", "sha256:" + "b".repeat(64));
        var noSource = cli();
        int noSourceCode = noSource.run(new String[] {
            "cache", "warm", noSourceManifest.toString(), "--preset", "table-lite", "--cache",
            tempDir.resolve("no-source-cache").toString()
        });

        assertThat(noSourceCode).isEqualTo(1);
        assertThat(noSource.err()).contains("model source missing");
    }

    private Path manifest(String sourceLine, String sha) throws Exception {
        var manifest = tempDir.resolve("models.json");
        Files.writeString(
                manifest,
                """
                {
                  "presets": {
                    "table-lite": [
                      {
                        "name": "slanet-plus",
                        "version": "local",
                        %s
                        "sha256": "%s",
                        "sizeBytes": 16,
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
                """.formatted(sourceLine, sha),
                StandardCharsets.UTF_8);
        return manifest;
    }

    private TestCli cli() {
        return cli(Map.of());
    }

    private TestCli cli(Map<String, String> env) {
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

    private static String sha256Hex(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class TestCli {
        private final DocTruthCli cli;
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;

        private TestCli(DocTruthCli cli, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            this.cli = cli;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        int run(String[] args) {
            return cli.run(args);
        }

        String out() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }
}
