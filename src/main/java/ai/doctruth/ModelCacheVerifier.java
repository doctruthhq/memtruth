package ai.doctruth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Verifies local parser model artifacts before model-assisted parsing can run.
 *
 * @since 1.0.0
 */
public final class ModelCacheVerifier {

    private ModelCacheVerifier() {
        throw new AssertionError("no instances");
    }

    public static ModelCacheReport verify(Path cacheDir, List<ModelDescriptor> descriptors) {
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(descriptors, "descriptors");
        var artifacts = new ArrayList<ModelCacheArtifact>(descriptors.size());
        var warnings = new ArrayList<ParserWarning>();
        for (ModelDescriptor descriptor : descriptors) {
            verifyOne(cacheDir, descriptor, artifacts, warnings);
        }
        return new ModelCacheReport(artifacts, warnings);
    }

    private static void verifyOne(
            Path cacheDir,
            ModelDescriptor descriptor,
            List<ModelCacheArtifact> artifacts,
            List<ParserWarning> warnings) {
        var path = cacheDir.resolve(descriptor.cacheFilename());
        if (!Files.isRegularFile(path)) {
            artifacts.add(new ModelCacheArtifact(descriptor, ModelCacheStatus.MISSING, 0, ""));
            warnings.add(new ParserWarning(
                    "model_missing",
                    ParserWarningSeverity.SEVERE,
                    "missing parser model artifact: " + descriptor.identity()));
            return;
        }
        try {
            long size = Files.size(path);
            String actualSha = "sha256:" + sha256Hex(path);
            if (!actualSha.equals(descriptor.sha256())) {
                artifacts.add(new ModelCacheArtifact(descriptor, ModelCacheStatus.SHA_MISMATCH, size, actualSha));
                warnings.add(new ParserWarning(
                        "model_sha_mismatch",
                        ParserWarningSeverity.SEVERE,
                        "model artifact SHA-256 mismatch: " + descriptor.identity()));
                return;
            }
            artifacts.add(new ModelCacheArtifact(descriptor, ModelCacheStatus.READY, size, actualSha));
        } catch (IOException e) {
            artifacts.add(new ModelCacheArtifact(descriptor, ModelCacheStatus.MISSING, 0, ""));
            warnings.add(new ParserWarning(
                    "model_missing",
                    ParserWarningSeverity.SEVERE,
                    "cannot read parser model artifact: " + descriptor.identity()));
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        var digest = sha256();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be supported by every JDK", e);
        }
    }
}
