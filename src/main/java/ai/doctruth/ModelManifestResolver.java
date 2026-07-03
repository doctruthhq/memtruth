package ai.doctruth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ModelManifestResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelManifestResolver() {
        throw new AssertionError("no instances");
    }

    static List<ModelDescriptor> requiredModels(ParserPreset preset) {
        return requiredArtifacts(preset).stream()
                .map(ModelManifestArtifact::descriptor)
                .toList();
    }

    static List<ModelManifestArtifact> requiredArtifacts(ParserPreset preset) {
        return manifestPath()
                .flatMap(path -> artifactsFromManifest(path, preset))
                .orElseGet(() -> preset.runtimePolicy().requiredModels().stream()
                        .map(ModelManifestArtifact::fromDescriptor)
                        .toList());
    }

    private static Optional<List<ModelManifestArtifact>> artifactsFromManifest(Path manifest, ParserPreset preset) {
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            var presetNode = MAPPER.readTree(manifest.toFile()).path("presets").path(preset.id());
            if (!presetNode.isArray()) {
                return Optional.empty();
            }
            var models = new ArrayList<ModelManifestArtifact>();
            for (JsonNode node : presetNode) {
                models.add(artifact(node));
            }
            return models.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(models));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read model manifest: " + manifest, e);
        }
    }

    private static ModelManifestArtifact artifact(JsonNode node) {
        var descriptor = new ModelDescriptor(
                requiredText(node, "name"),
                requiredText(node, "version"),
                requiredText(node, "sha256"),
                node.path("sizeBytes").asLong(),
                node.path("required").asBoolean(true));
        var runtime = new ModelRuntimeHints(
                optionalText(node, "task"),
                optionalText(node, "backend"),
                optionalText(node, "format"),
                optionalText(node, "precision"),
                optionalText(node, "license"));
        return new ModelManifestArtifact(descriptor, runtime, optionalSource(node));
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("model manifest missing field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static Optional<String> optionalSource(JsonNode node) {
        var source = node.path("source").asText("");
        return source.isBlank() ? Optional.empty() : Optional.of(source);
    }

    private static Optional<Path> manifestPath() {
        return setting("doctruth.model.manifest")
                .or(() -> environment("DOCTRUTH_MODEL_MANIFEST"))
                .map(Path::of);
    }

    private static Optional<String> setting(String key) {
        return Optional.ofNullable(System.getProperty(key)).filter(value -> !value.isBlank());
    }

    private static Optional<String> environment(String key) {
        return Optional.ofNullable(System.getenv(key)).filter(value -> !value.isBlank());
    }
}
