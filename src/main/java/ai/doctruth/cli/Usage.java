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
                  doctruth parse <document> [--parser opendataloader|pdfbox] [--format json|parsed-json] [--json] [--bboxes] [-o parsed.json]
                  doctruth profile <document> [--parser opendataloader|pdfbox] [--iterations n] [--include-output parser-only|trust-json|trust-json-file|parsed-json|parsed-json-file] [--json]
                  doctruth schema <schema.json> [--json]
                  doctruth extract <document> -s <schema.json> [--evidence-first] [-o out/]
                  doctruth audit <audit.json> [--json]
                  doctruth doctor [--json]
                  doctruth completion <bash|zsh|fish>
                  doctruth version

                Common:
                  doctruth parse contract.pdf --format json -o trust-document.json
                  doctruth profile contract.pdf --include-output trust-json --json
                  doctruth schema contract.schema.json
                  doctruth extract contract.pdf -s contract.schema.json
                  doctruth doctor
                """;
    }
}
