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

    @Test
    void cliInstallAndReleasePackagesModelWorkers() throws Exception {
        String install = Files.readString(Path.of("scripts/install-cli.sh"));
        String release = Files.readString(Path.of("scripts/package-cli-release.sh"));
        String smoke = Files.readString(Path.of("scripts/smoke-cli-release.sh"));
        String realModelSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-model-artifact.sh"));
        String realOcrCorpusSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-ocr-corpus.sh"));
        String realTatrSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-tatr-artifact.sh"));
        String realRtDetrSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-rtdetr-artifact.sh"));
        String realSlanextSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-slanext-artifact.sh"));
        String realModelSuiteSmoke = Files.readString(Path.of("scripts/smoke-doctruth-real-model-suite.sh"));
        String runtimeRealModelArtifactsSmoke =
                Files.readString(Path.of("scripts/smoke-doctruth-runtime-real-model-artifacts.sh"));
        String runtimeRealOcrCorpusSmoke =
                Files.readString(Path.of("scripts/smoke-doctruth-runtime-real-ocr-corpus.sh"));
        String runtimeRealSlanextArtifactSmoke =
                Files.readString(Path.of("scripts/smoke-doctruth-runtime-real-slanext-artifact.sh"));
        String runtimeOcrWorkerSmoke = Files.readString(Path.of("scripts/smoke-doctruth-runtime-ocr-worker.sh"));
        String runtimeSlanextWorkerSmoke =
                Files.readString(Path.of("scripts/smoke-doctruth-runtime-slanext-worker.sh"));
        String parserAccuracySeedSmoke =
                Files.readString(Path.of("scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh"));

        assertThat(install)
                .contains("doctruth-runtime")
                .contains("DOCTRUTH_RUNTIME_COMMAND")
                .contains("doctruth-rapidocr-mnn-worker")
                .contains("doctruth-slanext-table-worker")
                .contains("doctruth-onnx-model-worker")
                .contains("doctruth_onnx_worker_lib.py")
                .contains("smoke-doctruth-real-model-suite.sh")
                .contains("smoke-doctruth-runtime-real-model-artifacts.sh")
                .contains("smoke-doctruth-runtime-real-ocr-corpus.sh")
                .contains("smoke-doctruth-runtime-real-slanext-artifact.sh")
                .contains("smoke-doctruth-runtime-ocr-worker.sh")
                .contains("smoke-doctruth-runtime-slanext-worker.sh");
        assertThat(release)
                .contains("doctruth-runtime")
                .contains("DOCTRUTH_RUNTIME_COMMAND")
                .contains("doctruth-rapidocr-mnn-worker")
                .contains("doctruth-slanext-table-worker")
                .contains("doctruth-onnx-model-worker")
                .contains("doctruth_onnx_worker_lib.py")
                .contains("smoke-doctruth-real-model-suite.sh")
                .contains("smoke-doctruth-runtime-real-model-artifacts.sh")
                .contains("smoke-doctruth-runtime-real-ocr-corpus.sh")
                .contains("smoke-doctruth-runtime-real-slanext-artifact.sh")
                .contains("smoke-doctruth-runtime-ocr-worker.sh")
                .contains("smoke-doctruth-runtime-slanext-worker.sh");
        assertThat(smoke)
                .contains("doctruth-runtime")
                .contains("DOCTRUTH_RUNTIME_COMMAND")
                .contains("doctruth-rapidocr-mnn-worker")
                .contains("doctruth-slanext-table-worker")
                .contains("doctruth-onnx-model-worker")
                .contains("doctruth_onnx_worker_lib.py")
                .contains("DOCTRUTH_RAPIDOCR_BACKEND=mnn")
                .contains("backendReady");
        assertThat(realModelSmoke)
                .contains("DOCTRUTH_REAL_MODEL_MANIFEST")
                .contains("DOCTRUTH_REAL_MODEL_EXPECTED_ID")
                .contains("DOCTRUTH_REAL_MODEL_EXPECTED_TASK")
                .contains("DOCTRUTH_REAL_MODEL_SOURCE_PDF")
                .contains("cache warm")
                .contains("doctruth-onnx-model-worker")
                .contains("rust-sidecar+model-worker");
        assertThat(realOcrCorpusSmoke)
                .contains("DOCTRUTH_REAL_OCR_CORPUS_SMOKE")
                .contains("DOCTRUTH_REAL_OCR_MIN_ACCURACY")
                .contains("benchmark-corpus")
                .contains("ocr_text_accuracy")
                .contains("doctruth-rapidocr-mnn-worker");
        assertThat(realTatrSmoke)
                .contains("DOCTRUTH_REAL_TATR_SMOKE")
                .contains("Xenova/table-transformer-structure-recognition")
                .contains("onnx/model_quantized.onnx")
                .contains("DOCTRUTH_REAL_MODEL_MANIFEST")
                .contains("DOCTRUTH_REAL_MODEL_EXPECTED_ID")
                .contains("table-structure-recognition")
                .contains("rowRange")
                .contains("columnRange")
                .contains("doctruth-onnx-model-worker");
        assertThat(realRtDetrSmoke)
                .contains("DOCTRUTH_REAL_RTDETR_SMOKE")
                .contains("Kreuzberg/layout-models")
                .contains("rtdetr/model.onnx")
                .contains("layout-detection")
                .contains("orig_target_sizes")
                .contains("doctruth-onnx-model-worker");
        assertThat(realSlanextSmoke)
                .contains("DOCTRUTH_REAL_SLANEXT_SMOKE")
                .contains("DOCTRUTH_SLANEXT_PYTHON")
                .contains("doctruth-slanext-table-worker")
                .contains("table-server")
                .contains("table-structure-recognition")
                .contains("paddleocr");
        assertThat(realModelSuiteSmoke)
                .contains("DOCTRUTH_REAL_MODEL_SUITE")
                .contains("smoke-doctruth-real-rtdetr-artifact.sh")
                .contains("smoke-doctruth-real-tatr-artifact.sh")
                .contains("smoke-doctruth-real-slanext-artifact.sh");
        assertThat(runtimeRealModelArtifactsSmoke)
                .contains("DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS")
                .contains("DOCTRUTH_RUNTIME_MODEL_COMMAND")
                .contains("doctruth-runtime")
                .contains("parse_pdf")
                .contains("kreuzberg-rtdetr-layout")
                .contains("xenova-table-transformer-structure-recognition")
                .contains("doctruth-onnx-model-worker")
                .contains("rust-sidecar+model-worker");
        assertThat(runtimeRealOcrCorpusSmoke)
                .contains("DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE")
                .contains("DOCTRUTH_RUNTIME_MODEL_COMMAND")
                .contains("doctruth-runtime")
                .contains("parse_pdf")
                .contains("doctruth-rapidocr-mnn-worker")
                .contains("rapidocr-worker")
                .contains("ocr-router:v1");
        assertThat(runtimeRealSlanextArtifactSmoke)
                .contains("DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE")
                .contains("DOCTRUTH_SLANEXT_VENV")
                .contains("DOCTRUTH_SLANEXT_PADDLE_PACKAGE")
                .contains("paddlepaddle")
                .contains("DOCTRUTH_RUNTIME_MODEL_COMMAND")
                .contains("doctruth-runtime")
                .contains("parse_pdf")
                .contains("doctruth-slanext-table-worker")
                .contains("slanext-wired:paddleocr-runtime");
        assertThat(runtimeOcrWorkerSmoke)
                .contains("DOCTRUTH_RUNTIME_MODEL_COMMAND")
                .contains("doctruth-rapidocr-mnn-worker")
                .contains("OCR_REGION")
                .contains("rust-sidecar+model-worker");
        assertThat(runtimeSlanextWorkerSmoke)
                .contains("DOCTRUTH_RUNTIME_MODEL_COMMAND")
                .contains("doctruth-slanext-table-worker")
                .contains("TABLE_CELL")
                .contains("rust-sidecar+model-worker");
        assertThat(parserAccuracySeedSmoke)
                .contains("qualityProfile")
                .contains("parser-accuracy")
                .contains("multi-layout")
                .contains("table")
                .contains("ocr")
                .contains("bbox")
                .contains("source-map")
                .contains("benchmark-corpus");
    }
}
