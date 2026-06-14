package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class WorkflowContractTest {

    @Test
    void releaseWorkflowRunsRealModelSuiteWithPinnedRuntimeDependencies() throws Exception {
        String release = Files.readString(Path.of(".github/workflows/release.yml"));

        assertThat(release)
                .contains("actions/setup-python@v5")
                .contains("python-version: '3.10'")
                .contains("poppler-utils")
                .contains("onnxruntime==1.26.0")
                .contains("pillow>=12,<13")
                .contains("numpy<2.4")
                .contains("paddleocr==3.7.0")
                .contains("paddlepaddle==3.3.1")
                .contains("DOCTRUTH_REAL_MODEL_SUITE: '1'")
                .contains("DOCTRUTH_SLANEXT_PYTHON")
                .contains("scripts/smoke-doctruth-real-model-suite.sh");
    }

    @Test
    void ciWorkflowExercisesRealModelSuiteSkipPath() throws Exception {
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));

        assertThat(ci)
                .contains("Smoke real model suite skip path")
                .contains("scripts/smoke-doctruth-real-model-suite.sh");
    }

    @Test
    void ciWorkflowRunsParserAccuracySeedCorpusSmoke() throws Exception {
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));

        assertThat(ci)
                .contains("Smoke parser accuracy seed corpus")
                .contains("scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh");
    }
}
