package ai.doctruth.cli;

enum IncludeOutput {
    PARSER_ONLY("parser-only"),
    TRUST_JSON("trust-json"),
    PARSED_JSON("parsed-json"),
    TRUST_JSON_FILE("trust-json-file"),
    PARSED_JSON_FILE("parsed-json-file");

    private final String id;

    IncludeOutput(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static IncludeOutput parse(String raw) {
        return switch (raw) {
            case "parser-only", "none" -> PARSER_ONLY;
            case "trust-json", "json", "trust-document-json" -> TRUST_JSON;
            case "parsed-json" -> PARSED_JSON;
            case "trust-json-file", "trust-document-json-file", "json-file" -> TRUST_JSON_FILE;
            case "parsed-json-file" -> PARSED_JSON_FILE;
            default -> throw new UsageException("unsupported profile output mode: " + raw);
        };
    }
}
