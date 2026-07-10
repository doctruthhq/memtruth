package ai.doctruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DocTruthCliDoctorCompletionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void doctorReportsRuntimeAndConfigurationReadiness() {
        var cli = cli(Map.of("OPENAI_API_KEY", "test-key"));

        int code = cli.run(new String[] {"doctor"});

        assertThat(code).isZero();
        assertThat(cli.out())
                .contains("DocTruth doctor")
                .contains("java:")
                .contains("project:")
                .contains("OPENAI_API_KEY: set")
                .contains("ready:");
    }

    @Test
    void doctorJsonReportsMachineReadableReadiness() throws Exception {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"doctor", "--json"});

        assertThat(code).isZero();
        var tree = MAPPER.readTree(cli.out());
        assertThat(tree.path("java").path("version").asText()).isNotBlank();
        assertThat(tree.path("env").path("OPENAI_API_KEY").asBoolean()).isFalse();
    }

    @Test
    void completionPrintsShellScript() {
        var cli = cli(Map.of());

        int code = cli.run(new String[] {"completion", "bash"});

        assertThat(code).isZero();
        assertThat(cli.out()).contains("_doctruth").contains("doctor").contains("completion");
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
