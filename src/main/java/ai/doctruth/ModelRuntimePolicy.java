package ai.doctruth;

import java.util.List;
import java.util.Objects;

/**
 * Local model runtime policy for parser presets.
 *
 * @param offlineMode         true when network access is forbidden.
 * @param allowModelDownloads true when missing model artifacts may be downloaded.
 * @param requiredModels      model artifacts required by the selected preset.
 * @since 1.0.0
 */
public record ModelRuntimePolicy(boolean offlineMode, boolean allowModelDownloads, List<ModelDescriptor> requiredModels) {

    public ModelRuntimePolicy {
        Objects.requireNonNull(requiredModels, "requiredModels");
        requiredModels = List.copyOf(requiredModels);
    }

    public static ModelRuntimePolicy liteOffline() {
        return new ModelRuntimePolicy(true, false, List.of());
    }

    public static ModelRuntimePolicy offlineRequired(List<ModelDescriptor> requiredModels) {
        return new ModelRuntimePolicy(true, false, requiredModels);
    }

    public boolean networkAccessRequired() {
        return !offlineMode && allowModelDownloads && requiredModels.stream().anyMatch(ModelDescriptor::required);
    }

    public List<ParserWarning> warnings() {
        if (!offlineMode || requiredModels.isEmpty()) {
            return List.of();
        }
        return requiredModels.stream()
                .filter(ModelDescriptor::required)
                .map(model -> new ParserWarning(
                        "model_unavailable_fallback",
                        ParserWarningSeverity.SEVERE,
                        "required parser model "
                                + model.identity()
                                + " is unavailable in offline mode; expected "
                                + model.sha256()))
                .toList();
    }
}
