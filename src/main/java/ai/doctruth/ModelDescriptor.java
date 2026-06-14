package ai.doctruth;

import java.util.Objects;

/**
 * Model artifact identity for parser runtime planning and doctor checks.
 *
 * @param name      model name.
 * @param version   model version.
 * @param sha256    expected SHA-256 digest string.
 * @param sizeBytes expected model artifact size in bytes.
 * @param required  true when parsing quality depends on this model.
 * @since 1.0.0
 */
public record ModelDescriptor(String name, String version, String sha256, long sizeBytes, boolean required) {

    public ModelDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        requireNotBlank("name", name);
        requireNotBlank("version", version);
        requireNotBlank("sha256", sha256);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
    }

    private static void requireNotBlank(String name, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public String identity() {
        return name + ":" + version;
    }

    public String cacheFilename() {
        return sanitize(name) + "-" + sanitize(version) + ".bin";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
