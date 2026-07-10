package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaBaselineContractTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("memtruth.repoRoot", ".."))
            .toAbsolutePath()
            .normalize();

    @Test
    @DisplayName("Maven compiler release is Java 25")
    void mavenCompilerReleaseIsJava25() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<maven.compiler.release>25</maven.compiler.release>");
        assertThat(pom).contains("<release>25</release>");
        assertThat(pom).doesNotContain("<maven.compiler.release>21</maven.compiler.release>");
        assertThat(pom).doesNotContain("<release>21</release>");
    }

    @Test
    @DisplayName("GitHub workflows use Java 25")
    void githubWorkflowsUseJava25() throws IOException {
        var workflowTexts = Map.of(
                ".github/workflows/ci.yml", Files.readString(REPO_ROOT.resolve(".github/workflows/ci.yml")),
                ".github/workflows/release.yml", Files.readString(REPO_ROOT.resolve(".github/workflows/release.yml")));

        assertThat(workflowTexts.get(".github/workflows/ci.yml")).contains("java: ['25']");
        assertThat(workflowTexts.get(".github/workflows/release.yml")).contains("Set up JDK 25");
        assertThat(workflowTexts.get(".github/workflows/release.yml")).contains("java-version: '25'");
    }

    @Test
    @DisplayName("current public docs and examples advertise Java 25, not Java 21")
    void currentDocsAdvertiseJava25() throws IOException {
        for (Path path : currentJavaBaselineDocs()) {
            String text = Files.readString(path);

            assertThat(text).as(path.toString()).contains("Java 25");
            assertThat(text).as(path.toString()).doesNotContain("Java 21");
            assertThat(text).as(path.toString()).doesNotContain("Java 21+");
        }
    }

    private static Path[] currentJavaBaselineDocs() {
        return new Path[] {
            REPO_ROOT.resolve("CONTRIBUTING.md"),
            REPO_ROOT.resolve("README.md"),
            REPO_ROOT.resolve("examples/quickstart/README.md"),
            REPO_ROOT.resolve("examples/quickstart/Quickstart.java")
        };
    }
}
