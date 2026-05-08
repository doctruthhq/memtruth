package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CliPackagingContractTest {

    @Test
    void mavenJarManifestUsesDocTruthCliMainClass() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<mainClass>ai.doctruth.cli.DocTruthCli</mainClass>");
    }
}
