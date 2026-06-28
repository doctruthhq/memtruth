package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the sidecar process parser protocol. */
class SidecarParserBackendTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("sidecar backend advertises plain text as a first-class output profile")
    void sidecarCapabilitiesIncludePlainTextOutput() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                """);
        var backend = new SidecarParserBackend(runtime);

        assertThat(backend.capabilities().outputProfiles())
                .contains(
                        "json_full",
                        "markdown_clean",
                        "plain_text",
                        "compact_llm",
                        "html_review",
                        "content_blocks",
                        "parse_trace");
    }

    @Test
    @DisplayName("sidecar backend rejects missing runtime and non-positive timeouts")
    void constructorRejectsInvalidRuntimeAndTimeout() throws Exception {
        assertThatThrownBy(() -> new SidecarParserBackend(tempDir.resolve("missing-runtime")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular file");

        assertThatThrownBy(() -> new SidecarParserBackend(writePlainFile("runtime"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("sidecar doctor reports non-executable runtime as not healthy")
    void doctorReportsNonExecutableRuntime() throws Exception {
        var runtime = writePlainFile("non-executable-runtime");
        var backend = new SidecarParserBackend(runtime);

        var health = backend.doctor();

        assertThat(health.available()).isFalse();
        assertThat(health.warnings()).extracting(ParserWarning::code).containsExactly("sidecar_not_executable");
    }

    @Test
    @DisplayName("sidecar backend sends parse request on stdin and reads TrustDocument JSON from stdout")
    void parsesThroughSidecarProcess() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                REQ=$(cat)
                case "$REQ" in
                  *'"command":"parse_pdf"'*'"preset":"standard"'*) ;;
                  *) echo 'unexpected request' >&2; exit 7 ;;
                esac
                cat <<'JSON'
                {"docId":"sha256:sidecar","source":{"sourceFilename":"sidecar.pdf","sourceHash":"sha256:sidecar","metadata":{"sourceFilename":"sidecar.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"TEXT_BLOCK","page":1,"text":"Sidecar parsed text.","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10.0,"y0":20.0,"x1":200.0,"y1":80.0}},"sourceObjectId":"section-0001","confidence":{"score":0.97,"rationale":"sidecar"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"standard","backend":"sidecar","models":["layout-rtdetr:v2"],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """);
        var backend = new SidecarParserBackend(runtime);
        var request = new ParserRequest(
                tempDir.resolve("sidecar.pdf"),
                "sha256:sidecar",
                new ParserRun("1.0.0", "standard", "sidecar", List.of("layout-rtdetr:v2"), List.of()),
                true,
                false);

        var trust = backend.parse(request);

        assertThat(trust.parserRun().backend()).isEqualTo("sidecar");
        assertThat(trust.parserRun().models()).containsExactly("layout-rtdetr:v2");
        assertThat(trust.toMarkdownClean()).contains("Sidecar parsed text.");
        assertThat(trust.body().units().getFirst().location().boundingBox()).isPresent();
    }

    @Test
    @DisplayName("sidecar backend preserves Rust layered output observations")
    void preservesRuntimeLayeredOutputObservations() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                cat <<'JSON'
                {"docId":"sha256:sidecar","source":{"sourceFilename":"sidecar.pdf","sourceHash":"sha256:sidecar","metadata":{"sourceFilename":"sidecar.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":true,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"TEXT_BLOCK","page":1,"text":"Sidecar parsed text.","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10.0,"y0":20.0,"x1":200.0,"y1":80.0}},"sourceObjectId":"section-0001","confidence":{"score":0.97,"rationale":"sidecar"},"warnings":[]}],"tables":[]},"contentBlocks":[{"blockId":"runtime-block-9999","type":"text","page":1,"readingOrder":1,"text":"Runtime content block","sourceUnitIds":["unit-0001"],"evidenceSpanIds":["span-0001"],"warnings":[]}],"parseTrace":{"traceId":"runtime-trace-9999","parserRunId":"parser-run-runtime","pages":[{"pageIndex":0,"pageNumber":1,"pageSize":{"width":1000,"height":1000},"readingBlocks":[]}],"warnings":[]},"parserRun":{"parserRunId":"parser-run-runtime","parserVersion":"runtime-test","preset":"standard","backend":"rust-sidecar","models":[],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """);
        var trust = new SidecarParserBackend(runtime).parse(request());
        var contentBlocks = new StringWriter();
        var parseTrace = new StringWriter();

        trust.writeContentBlocks(contentBlocks);
        trust.writeParseTrace(parseTrace);

        assertThat(contentBlocks.toString()).contains("runtime-block-9999");
        assertThat(parseTrace.toString()).contains("runtime-trace-9999");
    }

    @Test
    @DisplayName("sidecar backend forwards OCR worker configuration to Rust runtime")
    void forwardsOcrWorkerConfigurationToRuntime() throws Exception {
        var worker = tempDir.resolve("ocr-worker");
        Files.writeString(worker, "#!/usr/bin/env sh\nexit 0\n");
        assertThat(worker.toFile().setExecutable(true)).isTrue();
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                test "$DOCTRUTH_RUNTIME_MODEL_COMMAND" = "%s"
                cat <<'JSON'
                {"docId":"sha256:sidecar","source":{"sourceFilename":"ocr.pdf","sourceHash":"sha256:sidecar","metadata":{"sourceFilename":"ocr.pdf","pageCount":1}},"body":{"pages":[{"pageNumber":1,"width":1000,"height":1000,"textLayerAvailable":false,"imageHash":"sha256:image"}],"units":[{"unitId":"unit-0001","kind":"OCR_REGION","page":1,"text":"OCR through Rust worker.","evidenceSpanIds":["span-0001"],"location":{"page":1,"readingOrder":1,"boundingBox":{"x0":10.0,"y0":20.0,"x1":200.0,"y1":80.0}},"sourceObjectId":"ocr-0001","confidence":{"score":0.97,"rationale":"sidecar"},"warnings":[]}],"tables":[]},"parserRun":{"parserVersion":"runtime-test","preset":"ocr","backend":"rust-sidecar+model-worker","models":["ocr-router:v1"],"warnings":[]},"auditGradeStatus":"AUDIT_GRADE"}
                JSON
                """.formatted(worker.toString()));
        var request = new ParserRequest(
                tempDir.resolve("ocr.pdf"),
                "sha256:sidecar",
                new ParserRun("1.0.0", "ocr", "sidecar", List.of("ocr-router:v1"), List.of()),
                true,
                false);

        withSystemProperty("doctruth.ocr.command", worker.toString(), () -> {
            var trust = new SidecarParserBackend(runtime).parse(request);

            assertThat(trust.parserRun().backend()).isEqualTo("rust-sidecar+model-worker");
            assertThat(trust.toMarkdownClean()).contains("OCR through Rust worker.");
        });
    }

    @Test
    @DisplayName("sidecar backend maps non-zero exit to structured ParseException")
    void nonZeroExitMapsToParseException() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                echo 'runtime crashed' >&2
                exit 42
                """);
        var backend = new SidecarParserBackend(runtime);
        var request = request();

        assertThatThrownBy(() -> backend.parse(request))
                .isInstanceOf(ParseException.class)
                .extracting("errorCode")
                .isEqualTo("SIDECAR_RUNTIME_FAILED");
    }

    @Test
    @DisplayName("sidecar backend maps invalid stdout JSON to structured ParseException")
    void invalidJsonMapsToParseException() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                echo 'not json'
                """);
        var backend = new SidecarParserBackend(runtime);
        var request = request();

        assertThatThrownBy(() -> backend.parse(request))
                .isInstanceOf(ParseException.class)
                .extracting("errorCode")
                .isEqualTo("SIDECAR_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("sidecar backend maps timeout to structured ParseException")
    void timeoutMapsToParseException() throws Exception {
        var runtime = writeRuntime("""
                #!/usr/bin/env sh
                cat >/dev/null
                sleep 1
                """);
        var backend = new SidecarParserBackend(runtime, Duration.ofMillis(10));
        var request = request();

        assertThatThrownBy(() -> backend.parse(request))
                .isInstanceOf(ParseException.class)
                .extracting("errorCode")
                .isEqualTo("SIDECAR_RUNTIME_TIMEOUT");
    }

    @Test
    @DisplayName("sidecar backend maps start failures to structured ParseException")
    void startFailureMapsToParseException() throws Exception {
        var runtime = writePlainFile("not-executable-runtime");
        var backend = new SidecarParserBackend(runtime);
        var request = request();

        assertThatThrownBy(() -> backend.parse(request))
                .isInstanceOf(ParseException.class)
                .extracting("errorCode")
                .isEqualTo("SIDECAR_START_FAILED");
    }

    private ParserRequest request() {
        return new ParserRequest(
                tempDir.resolve("input.pdf"),
                "sha256:input",
                new ParserRun("1.0.0", "standard", "sidecar", List.of(), List.of()),
                true,
                false);
    }

    private Path writeRuntime(String script) throws Exception {
        var runtime = tempDir.resolve("doctruth-runtime");
        Files.writeString(runtime, script);
        runtime.toFile().setExecutable(true);
        return runtime;
    }

    private Path writePlainFile(String name) throws Exception {
        var runtime = tempDir.resolve(name);
        Files.writeString(runtime, "not executable");
        runtime.toFile().setExecutable(false);
        return runtime;
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        var previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
