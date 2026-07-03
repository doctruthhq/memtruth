package ai.doctruth;

import java.util.Objects;
import java.util.Optional;

record ModelManifestArtifact(ModelDescriptor descriptor, ModelRuntimeHints runtime, Optional<String> source) {

    ModelManifestArtifact {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(source, "source");
        source = source.filter(value -> !value.isBlank());
    }

    static ModelManifestArtifact fromDescriptor(ModelDescriptor descriptor) {
        return new ModelManifestArtifact(descriptor, ModelRuntimeHints.empty(), Optional.empty());
    }
}
