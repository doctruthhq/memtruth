package ai.doctruth.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.doctruth.ModelCacheArtifact;
import ai.doctruth.ModelCacheReport;
import ai.doctruth.ModelCacheStatus;
import ai.doctruth.ModelCacheVerifier;
import ai.doctruth.ModelDescriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class CacheCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    CacheCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        if (args.length < 2 || !"warm".equals(args[1])) {
            throw new UsageException("usage: doctruth cache warm <manifest.json> --preset <preset> [--cache <dir>] [--offline] [--json]");
        }
        var options = Options.parse(args, context.env());
        var result = warm(options);
        if (options.json) {
            context.out().println(json(result));
        } else {
            context.out().println(result.report().allReady() ? "model cache ready" : "model cache incomplete");
        }
        if (!result.report().allReady()) {
            throw new CliException("model cache incomplete");
        }
    }

    private static Result warm(Options options) throws CliException {
        try {
            Files.createDirectories(options.cacheDir);
            var specs = specs(options.manifest, options.preset);
            installMissing(options, specs);
            return new Result(options.cacheDir, ModelCacheVerifier.verify(options.cacheDir, descriptors(specs)), specs);
        } catch (IOException e) {
            throw new CliException("failed to warm model cache: " + e.getMessage(), e);
        }
    }

    private static void installMissing(Options options, List<ModelSpec> specs) throws IOException, CliException {
        var report = ModelCacheVerifier.verify(options.cacheDir, descriptors(specs));
        for (int i = 0; i < specs.size(); i++) {
            if (report.artifacts().get(i).status() == ModelCacheStatus.READY) {
                continue;
            }
            var spec = specs.get(i);
            var source = spec.source()
                    .orElseThrow(() -> new CliException("model source missing: " + spec.descriptor().identity()));
            installSource(options, spec, source);
        }
    }

    private static void installSource(Options options, ModelSpec spec, String source) throws IOException, CliException {
        var target = options.cacheDir.resolve(spec.descriptor().cacheFilename());
        if (source.startsWith("http://") || source.startsWith("https://")) {
            downloadRemote(source, target, options.offline);
            return;
        }
        Files.copy(localSourcePath(options.manifest, source), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void downloadRemote(String source, Path target, boolean offline) throws IOException, CliException {
        if (offline) {
            throw new CliException("offline mode refuses remote model source: " + source);
        }
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        var request = HttpRequest.newBuilder(URI.create(source))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("remote model download interrupted: " + source, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(tmp);
            throw new CliException("remote model download failed " + response.statusCode() + ": " + source);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path localSourcePath(Path manifest, String source) {
        if (source.startsWith("file://")) {
            return Path.of(URI.create(source));
        }
        var path = Path.of(source);
        return path.isAbsolute() ? path : manifest.toAbsolutePath().getParent().resolve(path).normalize();
    }

    private static List<ModelSpec> specs(Path manifest, String preset) throws IOException {
        var node = MAPPER.readTree(manifest.toFile()).path("presets").path(preset);
        if (!node.isArray()) {
            throw new UsageException("model manifest preset not found: " + preset);
        }
        var specs = new ArrayList<ModelSpec>();
        for (JsonNode item : node) {
            specs.add(new ModelSpec(
                    new ModelDescriptor(
                            requiredText(item, "name"),
                            requiredText(item, "version"),
                            requiredText(item, "sha256"),
                            item.path("sizeBytes").asLong(0),
                            item.path("required").asBoolean(true)),
                    optionalText(item, "source"),
                    optionalTextValue(item, "task"),
                    optionalTextValue(item, "backend"),
                    optionalTextValue(item, "format"),
                    optionalTextValue(item, "precision"),
                    optionalTextValue(item, "license")));
        }
        return List.copyOf(specs);
    }

    private static List<ModelDescriptor> descriptors(List<ModelSpec> specs) {
        return specs.stream().map(ModelSpec::descriptor).toList();
    }

    private static String json(Result result) throws CliException {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("cacheDir", result.cacheDir().toString());
            root.put("allReady", result.report().allReady());
            root.put("networkAccessRequired", false);
            root.put("totalSizeBytes", result.report().totalSizeBytes());
            root.set("artifacts", artifacts(result.cacheDir(), result.report(), result.specs()));
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new CliException("failed to render cache JSON: " + e.getMessage(), e);
        }
    }

    private static ArrayNode artifacts(Path cacheDir, ModelCacheReport report, List<ModelSpec> specs) {
        ArrayNode artifacts = MAPPER.createArrayNode();
        for (ModelCacheArtifact artifact : report.artifacts()) {
            var model = artifact.descriptor();
            var spec = specs.stream()
                    .filter(candidate -> candidate.descriptor().identity().equals(model.identity()))
                    .findFirst()
                    .orElse(ModelSpec.fromDescriptor(model));
            ObjectNode item = MAPPER.createObjectNode();
            item.put("name", model.name());
            item.put("version", model.version());
            item.put("identity", model.identity());
            item.put("status", artifact.status().name());
            item.put("cachePath", cacheDir.resolve(model.cacheFilename()).toString());
            item.put("actualSizeBytes", artifact.actualSizeBytes());
            item.put("actualSha256", artifact.actualSha256());
            item.put("task", spec.task());
            item.put("backend", spec.backend());
            item.put("format", spec.format());
            item.put("precision", spec.precision());
            item.put("license", spec.license());
            artifacts.add(item);
        }
        return artifacts;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new UsageException("model manifest missing field: " + field);
        }
        return value;
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        var value = node.path(field).asText("");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String optionalTextValue(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private record ModelSpec(
            ModelDescriptor descriptor,
            Optional<String> source,
            String task,
            String backend,
            String format,
            String precision,
            String license) {

        private ModelSpec {
            task = task.trim();
            backend = backend.trim();
            format = format.trim();
            precision = precision.trim();
            license = license.trim();
        }

        static ModelSpec fromDescriptor(ModelDescriptor descriptor) {
            return new ModelSpec(descriptor, Optional.empty(), "", "", "", "", "");
        }
    }

    private record Result(Path cacheDir, ModelCacheReport report, List<ModelSpec> specs) {}

    private record Options(Path manifest, String preset, Path cacheDir, boolean offline, boolean json) {
        static Options parse(String[] args, Map<String, String> env) {
            var cursor = new ArgCursor(args, 2);
            Path manifest = cursor.nextPath("manifest");
            String preset = "";
            Path cacheDir = null;
            boolean offline = false;
            boolean json = false;
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "--preset" -> preset = cursor.next();
                    case "--cache" -> cacheDir = cursor.nextPath("--cache");
                    case "--offline" -> offline = true;
                    case "--json" -> json = true;
                    default -> throw new UsageException("unknown cache option: " + arg);
                }
            }
            if (preset.isBlank()) {
                throw new UsageException("--preset is required");
            }
            return new Options(manifest, preset, cacheDir == null ? defaultCache(env) : cacheDir, offline, json);
        }

        private static Path defaultCache(Map<String, String> env) {
            String configured = env.get("DOCTRUTH_MODEL_CACHE");
            return configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".cache", "doctruth", "models")
                    : Path.of(configured);
        }
    }
}
