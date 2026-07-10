package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AuditCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliContext context;

    AuditCommand(CliContext context) {
        this.context = context;
    }

    void run(String[] args) throws CliException {
        var options = AuditOptions.parse(args);
        JsonNode audit = read(options.path());
        if (options.json()) {
            context.out().println(compactSummary(audit).toPrettyString());
            return;
        }
        print(audit);
    }

    private void print(JsonNode audit) {
        JsonNode derived = audit.path("prov:wasDerivedFrom");
        long weak = java.util.stream.StreamSupport.stream(derived.spliterator(), false)
                .filter(entry -> entry.path("doctruth:matchScore").asDouble(0.0) < 0.85)
                .count();
        context.out().println("fields: " + derived.size());
        context.out().println("cited: " + derived.size());
        context.out().println("weak matches: " + weak);
        context.out().println();
        for (JsonNode entry : derived) {
            printEntry(entry);
        }
    }

    private void printEntry(JsonNode entry) {
        var location = entry.path("doctruth:sourceLocation");
        context.out().println(entry.path("doctruth:fieldPath").asText());
        context.out().println("  quote: " + entry.path("prov:value").asText());
        context.out()
                .println("  page: " + location.path("pageStart").asInt() + " line: "
                        + location.path("lineStart").asInt());
        context.out()
                .printf("  match: %.2f%n", entry.path("doctruth:matchScore").asDouble());
    }

    private static JsonNode compactSummary(JsonNode audit) {
        var node = MAPPER.createObjectNode();
        JsonNode derived = audit.path("prov:wasDerivedFrom");
        node.put("fields", derived.size());
        node.put("cited", derived.size());
        return node;
    }

    private static JsonNode read(Path path) throws CliException {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new CliException("failed to read audit JSON " + path + ": " + e.getMessage(), e);
        }
    }

    private record AuditOptions(Path path, boolean json) {
        static AuditOptions parse(String[] args) {
            if (args.length < 2) {
                throw new UsageException("usage: doctruth audit <audit.json> [--json]");
            }
            Path path = Path.of(args[1]);
            boolean json = false;
            var cursor = new ArgCursor(args, 2);
            while (cursor.hasNext()) {
                String arg = cursor.next();
                if ("--json".equals(arg)) {
                    json = true;
                } else {
                    throw new UsageException("unknown audit option: " + arg);
                }
            }
            return new AuditOptions(path, json);
        }
    }
}
