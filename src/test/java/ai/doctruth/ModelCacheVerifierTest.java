package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for local model cache verification. */
class ModelCacheVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("model cache verifier accepts cached artifact with matching SHA-256")
    void matchingModelArtifactIsReady() throws Exception {
        byte[] bytes = "tiny fake model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var descriptor = new ModelDescriptor("tatr", "v1", "sha256:" + sha256(bytes), bytes.length, true);
        Files.write(tempDir.resolve(descriptor.cacheFilename()), bytes);

        var report = ModelCacheVerifier.verify(tempDir, List.of(descriptor));

        assertThat(report.allReady()).isTrue();
        assertThat(report.totalSizeBytes()).isEqualTo(bytes.length);
        assertThat(report.warnings()).isEmpty();
        assertThat(report.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.status()).isEqualTo(ModelCacheStatus.READY);
            assertThat(artifact.actualSha256()).isEqualTo(descriptor.sha256());
        });
    }

    @Test
    @DisplayName("model cache verifier accepts an empty required model list")
    void emptyRequiredModelListIsReady() {
        var report = ModelCacheVerifier.verify(tempDir, List.of());

        assertThat(report.allReady()).isTrue();
        assertThat(report.totalSizeBytes()).isZero();
        assertThat(report.artifacts()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("model cache verifier reports missing and SHA mismatch as blocking warnings")
    void missingAndMismatchedArtifactsAreWarnings() throws Exception {
        byte[] bytes = "wrong model bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var mismatch = new ModelDescriptor("layout-rtdetr", "v2", "sha256:not-the-real-hash", bytes.length, true);
        var missing = new ModelDescriptor("tatr", "v1", "sha256:missing", 30_000_000, true);
        Files.write(tempDir.resolve(mismatch.cacheFilename()), bytes);

        var report = ModelCacheVerifier.verify(tempDir, List.of(mismatch, missing));

        assertThat(report.allReady()).isFalse();
        assertThat(report.artifacts())
                .extracting(ModelCacheArtifact::status)
                .containsExactly(ModelCacheStatus.SHA_MISMATCH, ModelCacheStatus.MISSING);
        assertThat(report.warnings())
                .extracting(ParserWarning::code)
                .contains("model_sha_mismatch", "model_missing");
        assertThat(report.warnings())
                .extracting(ParserWarning::severity)
                .containsOnly(ParserWarningSeverity.SEVERE);
    }

    @Test
    @DisplayName("model cache verifier rejects null inputs")
    void rejectsNullInputs() {
        var descriptor = new ModelDescriptor("tatr", "v1", "sha256:" + "a".repeat(64), 1, true);

        assertThatNullPointerException()
                .isThrownBy(() -> ModelCacheVerifier.verify(null, List.of(descriptor)))
                .withMessageContaining("cacheDir");
        assertThatNullPointerException()
                .isThrownBy(() -> ModelCacheVerifier.verify(tempDir, null))
                .withMessageContaining("descriptors");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
