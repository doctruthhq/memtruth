package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocTruthSkillPackageContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @TempDir
    Path tempDir;

    @Test
    void skillPackageDocumentsAgentMcpEvidenceWorkflow() throws IOException {
        Path skill = ROOT.resolve("skills/doctruth/SKILL.md");
        Path metadata = ROOT.resolve("skills/doctruth/agents/openai.yaml");
        Path bootstrap = ROOT.resolve("skills/doctruth/scripts/bootstrap-local-mcp.sh");

        assertThat(skill).exists();
        assertThat(metadata).exists();
        assertThat(bootstrap).exists();

        String body = Files.readString(skill);
        assertThat(body).contains("name: doctruth");
        assertThat(body).contains("description:");
        assertThat(body).contains("doctruth mcp");
        assertThat(body).contains("doctruth.parse_document");
        assertThat(body).contains("doctruth.get_layout_regions");
        assertThat(body).contains("doctruth.get_table_cells");
        assertThat(body).contains("doctruth.get_evidence_span");
        assertThat(body).contains("doctruth.verify_citation");
        assertThat(Files.readString(metadata)).contains("display_name: \"DocTruth\"");
    }

    @Test
    void bootstrapScriptWritesLocalMcpConfig() throws Exception {
        Path out = tempDir.resolve("mcp.json");
        Path bootstrap = ROOT.resolve("skills/doctruth/scripts/bootstrap-local-mcp.sh");

        var process = new ProcessBuilder(
                        "sh",
                        bootstrap.toString(),
                        "--command",
                        "/opt/doctruth/bin/doctruth",
                        "--out",
                        out.toString())
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("wrote MCP config");
        var config = MAPPER.readTree(Files.readString(out));
        var server = config.path("mcpServers").path("doctruth");
        assertThat(server.path("command").asText()).isEqualTo("/opt/doctruth/bin/doctruth");
        assertThat(server.path("args").get(0).asText()).isEqualTo("mcp");
    }
}
