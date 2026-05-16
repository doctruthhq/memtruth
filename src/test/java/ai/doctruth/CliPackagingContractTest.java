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

    @Test
    void mavenBuildAttachesStandaloneCliJar() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<artifactId>maven-shade-plugin</artifactId>")
                .contains("<shadedClassifierName>all</shadedClassifierName>")
                .contains("<createDependencyReducedPom>false</createDependencyReducedPom>")
                .contains("org.slf4j:slf4j-nop:${slf4j.version}")
                .contains("org.apache.logging.log4j:log4j-to-slf4j:${log4j.version}");
    }
}
