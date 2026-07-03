package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExamplesCompileTest {

    private static final List<Path> EXAMPLE_SOURCES = List.of(
            Path.of("examples/quickstart/Quickstart.java"),
            Path.of("examples/pydantic-interop/PydanticInteropExample.java"),
            Path.of("examples/evidence-overlay/EvidenceOverlay.java"),
            Path.of("examples/no-llm-parse/NoLlmParse.java"));

    @Test
    @DisplayName("public Java examples compile against the project classpath")
    void publicJavaExamplesCompile() throws Exception {
        for (Path source : EXAMPLE_SOURCES) {
            assertThat(source).exists();
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();

        Path outputDir = Path.of("target/example-compile-test");
        Files.createDirectories(outputDir);
        var args = new java.util.ArrayList<String>();
        args.add("-cp");
        String classpath = publicExampleClasspath();
        assertThat(classpath).contains("target/classes");
        args.add(classpath);
        args.add("-d");
        args.add(outputDir.toString());
        EXAMPLE_SOURCES.stream().map(Path::toString).forEach(args::add);

        int exit = compiler.run(null, null, null, args.toArray(String[]::new));

        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("no-LLM sample document is tracked and parseable")
    void noLlmSampleDocumentIsParseable() throws Exception {
        Path sample = Path.of("examples/no-llm-parse/sample-contract.csv");

        assertThat(sample).exists();
        assertThat(Files.readString(sample).toLowerCase(Locale.ROOT)).contains("totalvalue");

        ParsedDocument doc = CsvDocumentParser.parse(sample);

        assertThat(doc.metadata().sourceFilename())
                .isEqualTo(sample.getFileName().toString());
        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().getFirst()).isInstanceOf(TableSection.class);
        assertThat(((TableSection) doc.sections().getFirst()).rows()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(Files.isRegularFile(sample)).isTrue();
    }

    private static String publicExampleClasspath() {
        String separator = System.getProperty("path.separator");
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(separator)))
                .filter(ExamplesCompileTest::isPublicClasspathEntry)
                .collect(Collectors.joining(separator));
    }

    private static boolean isPublicClasspathEntry(String entry) {
        return !entry.endsWith("target/test-classes")
                && !entry.contains("/junit-")
                && !entry.contains("/assertj-")
                && !entry.contains("/surefire-")
                && !entry.contains("/apiguardian-api/")
                && !entry.contains("/opentest4j/");
    }
}
