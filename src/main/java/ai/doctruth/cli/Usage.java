package ai.doctruth.cli;

final class Usage {

    private Usage() {
        throw new AssertionError("no instances");
    }

    static String main() {
        return """
                DocTruth - auditable LLM extraction for Java

                Usage:
                  doctruth init
                  doctruth parse <document> [--json] [--bboxes] [-o parsed.json]
                  doctruth ingest-audit <pdf-dir> [--json] [--limit N] [-o audit.json]
                  doctruth schema <schema.json> [--json]
                  doctruth extract <document> -s <schema.json> [-o out/]
                  doctruth audit <audit.json> [--json]
                  doctruth doctor [--json]
                  doctruth completion <bash|zsh|fish>
                  doctruth version

                Common:
                  doctruth parse contract.pdf
                  doctruth ingest-audit ./resumes --json -o ingest-audit.json
                  doctruth schema contract.schema.json
                  doctruth extract contract.pdf -s contract.schema.json
                  doctruth doctor
                """;
    }
}
