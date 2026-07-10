package ai.doctruth.cli;

import java.net.URI;
import java.util.Optional;

record ProviderConfig(String provider, Optional<String> model, Optional<URI> baseUrl, CliConfig config) {

    ProviderConfig {
        provider = provider == null || provider.isBlank() ? config.provider() : provider;
        model = model == null ? Optional.empty() : model;
        baseUrl = baseUrl == null ? Optional.empty() : baseUrl;
    }

    String effectiveModel(String fallback) {
        return model.or(() -> config.model()).orElse(fallback);
    }
}
