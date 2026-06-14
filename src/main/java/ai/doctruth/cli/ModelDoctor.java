package ai.doctruth.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.doctruth.ModelCacheArtifact;
import ai.doctruth.ModelCacheReport;
import ai.doctruth.ModelCacheStatus;
import ai.doctruth.ModelCacheVerifier;
import ai.doctruth.ModelDescriptor;
import ai.doctruth.ModelRuntimePolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

record ModelDoctor(
        Path cacheDirectory,
        CacheState cache,
        ModelWorkerDoctor worker) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static ModelDoctor local(Map<String, String> env) {
        Path cache = modelCache(env);
        var required = requiredModels(env);
        var report = ModelCacheVerifier.verify(cache, required.stream().map(ManifestModel::descriptor).toList());
        long estimatedBytes = required.stream().mapToLong(model -> model.descriptor().sizeBytes()).sum();
        return new ModelDoctor(
                cache,
                new CacheState(
                        Files.isDirectory(cache),
                        networkAccessRequired(report, required),
                        estimatedBytes / (1024 * 1024),
                        report,
                        required),
                ModelWorkerDoctor.local(env));
    }

    boolean cacheExists() {
        return cache.cacheExists();
    }

    int requiredModels() {
        return cache.requiredModels();
    }

    boolean networkAccessRequired() {
        return cache.networkAccessRequired();
    }

    long estimatedCacheMb() {
        return cache.estimatedCacheMb();
    }

    boolean allReady() {
        return cache.report().allReady();
    }

    List<ModelCacheArtifact> artifacts() {
        return cache.report().artifacts();
    }

    List<Map<String, Object>> artifactSummaries() {
        return artifacts().stream()
                .map(this::artifactSummary)
                .toList();
    }

    private Map<String, Object> artifactSummary(ModelCacheArtifact artifact) {
        var model = artifact.descriptor();
        var runtime = cache.models().stream()
                .filter(candidate -> candidate.descriptor().identity().equals(model.identity()))
                .findFirst()
                .map(ManifestModel::runtime)
                .orElse(RuntimeFields.empty());
        var item = new LinkedHashMap<String, Object>();
        item.put("name", model.name());
        item.put("version", model.version());
        item.put("identity", model.identity());
        item.put("status", artifact.status().name());
        item.put("cachePath", cacheDirectory.resolve(model.cacheFilename()).toString());
        item.put("actualSizeBytes", artifact.actualSizeBytes());
        item.put("actualSha256", artifact.actualSha256());
        item.put("task", runtime.task());
        item.put("backend", runtime.backend());
        item.put("format", runtime.format());
        item.put("precision", runtime.precision());
        item.put("license", runtime.license());
        return Map.copyOf(item);
    }


    private static Path modelCache(Map<String, String> env) {
        String configured = env.get("DOCTRUTH_MODEL_CACHE");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "doctruth", "models");
    }

    private static List<ManifestModel> requiredModels(Map<String, String> env) {
        String manifest = env.get("DOCTRUTH_MODEL_MANIFEST");
        if (manifest == null || manifest.isBlank()) {
            var policy = ModelRuntimePolicy.liteOffline();
            return policy.requiredModels().stream()
                    .map(model -> new ManifestModel(model, false, RuntimeFields.empty()))
                    .toList();
        }
        return manifestModels(Path.of(manifest));
    }

    private static List<ManifestModel> manifestModels(Path manifest) {
        try {
            return manifestModels(MAPPER.readTree(manifest.toFile()).path("presets"));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ManifestModel> manifestModels(JsonNode presets) {
        var models = new LinkedHashMap<String, ManifestModel>();
        presets.fields().forEachRemaining(entry -> appendPresetModels(entry.getValue(), models));
        return List.copyOf(models.values());
    }

    private static void appendPresetModels(JsonNode preset, Map<String, ManifestModel> models) {
        if (!preset.isArray()) {
            return;
        }
        for (JsonNode item : preset) {
            var descriptor = new ModelDescriptor(
                    requiredText(item, "name"),
                    requiredText(item, "version"),
                    requiredText(item, "sha256"),
                    item.path("sizeBytes").asLong(0),
                    item.path("required").asBoolean(true));
            models.putIfAbsent(descriptor.identity(), new ManifestModel(
                    descriptor,
                    remoteSource(item),
                    new RuntimeFields(
                            optionalText(item, "task"),
                            optionalText(item, "backend"),
                            optionalText(item, "format"),
                            optionalText(item, "precision"),
                            optionalText(item, "license"))));
        }
    }

    private static boolean networkAccessRequired(ModelCacheReport report, List<ManifestModel> models) {
        var missing = new ArrayList<String>();
        for (ModelCacheArtifact artifact : report.artifacts()) {
            if (artifact.status() != ModelCacheStatus.READY) {
                missing.add(artifact.descriptor().identity());
            }
        }
        return models.stream().anyMatch(model -> model.remoteSource() && missing.contains(model.descriptor().identity()));
    }

    private static boolean remoteSource(JsonNode node) {
        String source = node.path("source").asText("");
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("model manifest missing field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private record ManifestModel(ModelDescriptor descriptor, boolean remoteSource, RuntimeFields runtime) {}

    private record RuntimeFields(String task, String backend, String format, String precision, String license) {

        private RuntimeFields {
            task = task.trim();
            backend = backend.trim();
            format = format.trim();
            precision = precision.trim();
            license = license.trim();
        }

        static RuntimeFields empty() {
            return new RuntimeFields("", "", "", "", "");
        }
    }

    private record CacheState(
            boolean cacheExists,
            boolean networkAccessRequired,
            long estimatedCacheMb,
            ModelCacheReport report,
            List<ManifestModel> models) {

        int requiredModels() {
            return report.artifacts().size();
        }
    }
}
