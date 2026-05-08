package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthCliTest {

    @TempDir
    Path tempDir;

    @Test
    void migratePydanticExportsSchemaAndChecksCompatibility() throws Exception {
        Path schemaPath = tempDir.resolve("resume.schema.json");
        var cli = cliReturning("""
                {
                  "$defs": {
                    "Address": {
                      "type": "object",
                      "properties": { "city": { "type": "string" } }
                    }
                  },
                  "type": "object",
                  "properties": { "address": { "$ref": "#/$defs/Address" } },
                  "required": ["address"]
                }
                """);

        int code = cli.run(
                new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString(), "--check"
                });

        assertThat(code).isEqualTo(0);
        assertThat(Files.readString(schemaPath)).contains("\"$defs\"").contains("\"Address\"");
        assertThat(cli.out()).contains("schema compatible").contains(schemaPath.toString());
    }

    @Test
    void migratePydanticRejectsInvalidModelSpec() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {
            "migrate", "pydantic", "Resume", "--out", tempDir.resolve("x.json").toString()
        });

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("expected <module>:<Model>");
    }

    @Test
    void migratePydanticRejectsMissingOutOption() {
        var cli = cliReturning("{}");

        int code = cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume"});

        assertThat(code).isEqualTo(2);
        assertThat(cli.err()).contains("--out <schema.json> is required");
    }

    @Test
    void helpAndUnknownCommandsReturnUsage() {
        var help = cliReturning("{}");
        var unknown = cliReturning("{}");

        int helpCode = help.run(new String[] {"--help"});
        int unknownCode = unknown.run(new String[] {"parse"});

        assertThat(helpCode).isEqualTo(2);
        assertThat(help.err()).contains("usage: doctruth migrate pydantic");
        assertThat(unknownCode).isEqualTo(2);
        assertThat(unknown.err()).contains("usage: doctruth migrate pydantic");
    }

    @Test
    void migratePydanticCreatesOutputParents() throws Exception {
        Path schemaPath = tempDir.resolve("schemas/generated/resume.schema.json");
        var cli = cliReturning("{\"type\":\"object\"}");

        int code =
                cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString()});

        assertThat(code).isEqualTo(0);
        assertThat(Files.readString(schemaPath)).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void migratePydanticReportsInvalidExporterJson() {
        Path schemaPath = tempDir.resolve("invalid.schema.json");
        var cli = cliReturning("{not-json");

        int code =
                cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(Files.exists(schemaPath)).isFalse();
        assertThat(cli.err()).contains("exported Pydantic schema is not valid JSON");
    }

    @Test
    void migratePydanticReportsExporterIoFailure() {
        Path schemaPath = tempDir.resolve("resume.schema.json");
        var cli = cliWithExporter(spec -> {
            throw new IOException("python missing");
        });

        int code =
                cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(cli.err()).contains("failed to export Pydantic schema").contains("python missing");
    }

    @Test
    void migratePydanticPreservesInterruptedStatus() {
        Path schemaPath = tempDir.resolve("resume.schema.json");
        var cli = cliWithExporter(spec -> {
            throw new InterruptedException("cancelled");
        });

        int code =
                cli.run(new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(cli.err()).contains("Pydantic schema export interrupted");
        Thread.interrupted();
    }

    @Test
    void pythonPydanticExporterUsesConfiguredExecutable() throws Exception {
        Path pythonStub = tempDir.resolve("python-stub");
        Files.writeString(pythonStub, """
                #!/bin/sh
                printf '{"type":"object","properties":{"name":{"type":"string"}}}'
                """, StandardCharsets.UTF_8);
        assertThat(pythonStub.toFile().setExecutable(true)).isTrue();

        var exporter = new DocTruthCli.PythonPydanticExporter(Map.of("DOCTRUTH_PYTHON", pythonStub.toString()));

        assertThat(exporter.export("myapp.schemas:Resume")).contains("\"name\"");
    }

    @Test
    void pythonPydanticExporterReportsNonZeroExit() throws Exception {
        Path pythonStub = tempDir.resolve("python-fail");
        Files.writeString(pythonStub, """
                #!/bin/sh
                printf 'no pydantic' >&2
                exit 13
                """, StandardCharsets.UTF_8);
        assertThat(pythonStub.toFile().setExecutable(true)).isTrue();
        var exporter = new DocTruthCli.PythonPydanticExporter(Map.of("DOCTRUTH_PYTHON", pythonStub.toString()));

        assertThatThrownBy(() -> exporter.export("myapp.schemas:Resume"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("python exited 13")
                .hasMessageContaining("no pydantic");
    }

    @Test
    void migratePydanticCheckRejectsRemoteRefsBeforeWritingOutput() {
        Path schemaPath = tempDir.resolve("bad.schema.json");
        var cli = cliReturning("""
                {
                  "type": "object",
                  "properties": {
                    "address": { "$ref": "https://example.com/schemas/address.json" }
                  }
                }
                """);

        int code = cli.run(
                new String[] {"migrate", "pydantic", "myapp.schemas:Resume", "--out", schemaPath.toString(), "--check"
                });

        assertThat(code).isEqualTo(1);
        assertThat(Files.exists(schemaPath)).isFalse();
        assertThat(cli.err()).contains("unsupported $ref");
    }

    private TestCli cliReturning(String schemaJson) {
        return cliWithExporter(spec -> schemaJson);
    }

    private TestCli cliWithExporter(DocTruthCli.PydanticExporter exporter) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new DocTruthCli(
                Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                exporter);
        return new TestCli(cli, out, err);
    }

    private record TestCli(DocTruthCli delegate, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        int run(String[] args) {
            return delegate.run(args);
        }

        public String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }

        public String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
