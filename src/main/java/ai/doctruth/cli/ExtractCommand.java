package ai.doctruth.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import ai.doctruth.DocTruth;
import ai.doctruth.ExtractionException;
import ai.doctruth.ExtractionResult;
import ai.doctruth.JsonSchema;
import ai.doctruth.ParsedDocument;
import ai.doctruth.TrustDocumentJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ExtractCommand {

    private static final String DEFAULT_PROMPT = "Extract the document fields according to the supplied schema.";
    private static final String RUN_SCHEMA_VERSION = "doctruth.extract-run.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    ExtractCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = ExtractOptions.parse(args);
        var config = CliConfig.load(context.env());
        var doc = DocumentParsers.parse(options.document());
        var schema = SchemaCommand.readSchema(options.schema());
        var provider = context.providers().create(options.providerConfig(config));
        var result = runExtraction(doc, schema, provider, options);
        Path dir = options.out().orElseGet(() -> defaultRunDir(config));
        writeOutputs(dir, options.document(), doc, result);
        printSummary(dir, result);
    }

    private static ExtractionResult<JsonNode> runExtraction(
            ai.doctruth.ParsedDocument doc, JsonSchema schema, ai.doctruth.LlmProvider provider, ExtractOptions options)
            throws CliException {
        try {
            var builder = DocTruth.from(provider)
                    .extractJson(options.prompt(), schema)
                    .withProvenance()
                    .withConfidence()
                    .withBitemporal()
                    .withMaxRetries(2);
            if (options.evidenceFirst()) {
                builder = builder.withEvidenceFirst();
            }
            if (!options.allowUncited()) {
                for (String field : options.requiredFields(schema)) {
                    builder = builder.requireCitation(field);
                }
            }
            return builder.runJson(doc);
        } catch (ExtractionException e) {
            throw new CliException("extraction failed: " + e.getMessage(), e);
        }
    }

    private static void writeOutputs(
            Path dir, Path source, ParsedDocument doc, ExtractionResult<JsonNode> result) throws CliException {
        try {
            Files.createDirectories(dir);
            TrustDocumentJson.writeJson(doc, source, DocumentParsers.parserId(source), dir.resolve("trust-document.json"));
            Files.writeString(dir.resolve("result.json"), result.value().toPrettyString());
            result.toAuditJson(dir.resolve("audit.json"));
            Files.writeString(dir.resolve("manifest.json"), manifest(source, doc).toPrettyString());
        } catch (IOException e) {
            throw new CliException("failed to write extraction outputs: " + e.getMessage(), e);
        }
    }

    private static ObjectNode manifest(Path source, ParsedDocument doc) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", RUN_SCHEMA_VERSION);
        root.put("source", source.toString());
        root.put("docId", doc.docId());
        root.put("parser", DocumentParsers.parserId(source));
        ObjectNode artifacts = MAPPER.createObjectNode();
        artifacts.put("trustDocument", "trust-document.json");
        artifacts.put("result", "result.json");
        artifacts.put("audit", "audit.json");
        root.set("artifacts", artifacts);
        return root;
    }

    private void printSummary(Path dir, ExtractionResult<JsonNode> result) {
        int fields = result.value().isObject() ? result.value().size() : 1;
        long weak = result.citations().values().stream()
                .filter(c -> c.matchScore() < 0.85)
                .count();
        context.out().println("extracted");
        context.out().println("fields: " + fields);
        context.out().println("cited: " + result.citations().size());
        context.out().println("weak matches: " + weak);
        context.out().println("result: " + dir.resolve("result.json"));
        context.out().println("audit: " + dir.resolve("audit.json"));
    }

    private static Path defaultRunDir(CliConfig config) {
        String id = "run_" + Instant.now().toString().replaceAll("[^0-9A-Za-z]", "");
        return config.output().resolve(id);
    }

    private record ExtractOptions(
            Path document,
            Path schema,
            Optional<Path> out,
            String provider,
            Optional<String> model,
            Optional<URI> baseUrl,
            boolean allowUncited,
            boolean evidenceFirst,
            Set<String> require,
            String prompt) {

        static ExtractOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth extract <document> -s <schema.json> [-o out/]");
            }
            Path document = Path.of(args[1]);
            Path schema = null;
            Path out = null;
            String provider = null;
            Optional<String> model = Optional.empty();
            Optional<URI> baseUrl = Optional.empty();
            boolean allowUncited = false;
            boolean evidenceFirst = false;
            Set<String> require = new LinkedHashSet<>();
            String prompt = DEFAULT_PROMPT;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                switch (arg) {
                    case "-s", "--schema" -> schema = cursor.nextPath(arg);
                    case "-o", "--out" -> out = cursor.nextPath(arg);
                    case "--provider" -> provider = cursor.next();
                    case "--model" -> model = Optional.of(cursor.next());
                    case "--base-url" -> baseUrl = Optional.of(URI.create(cursor.next()));
                    case "--allow-uncited" -> allowUncited = true;
                    case "--evidence-first" -> evidenceFirst = true;
                    case "--require" -> addRequired(require, cursor.next());
                    case "--prompt" -> prompt = cursor.next();
                    default -> throw new UsageException("unknown extract option: " + arg);
                }
            }
            if (schema == null) {
                throw new UsageException("-s <schema.json> is required");
            }
            return new ExtractOptions(
                    document,
                    schema,
                    Optional.ofNullable(out),
                    provider,
                    model,
                    baseUrl,
                    allowUncited,
                    evidenceFirst,
                    Set.copyOf(require),
                    prompt);
        }

        ProviderConfig providerConfig(CliConfig config) {
            return new ProviderConfig(provider, model, baseUrl, config);
        }

        Set<String> requiredFields(JsonSchema schema) {
            if (!require.isEmpty()) {
                return require;
            }
            var fields = new LinkedHashSet<String>();
            collectLeafFields("", schema.node(), fields);
            return Set.copyOf(fields);
        }

        private static void collectLeafFields(String path, JsonNode schema, Set<String> fields) {
            var properties = schema.path("properties");
            if ("object".equals(schema.path("type").asText()) && properties.isObject()) {
                properties.fields().forEachRemaining(e -> collectLeafFields(joinPath(path, e.getKey()), e.getValue(), fields));
                return;
            }
            if ("array".equals(schema.path("type").asText()) && schema.has("items")) {
                collectLeafFields(path, schema.path("items"), fields);
                return;
            }
            if (!path.isBlank()) {
                fields.add(path);
            }
        }

        private static String joinPath(String parent, String child) {
            return parent.isEmpty() ? child : parent + "." + child;
        }

        private static void addRequired(Set<String> require, String csv) {
            for (String field : csv.split(",")) {
                if (!field.isBlank()) {
                    require.add(field.trim());
                }
            }
        }
    }
}
