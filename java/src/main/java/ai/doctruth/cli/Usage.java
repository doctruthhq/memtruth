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
                  doctruth parse <document> [--parser opendataloader|pdfbox] [--json] [--bboxes] [-o parsed.json]
                  doctruth schema <schema.json> [--json]
                  doctruth extract <document> -s <schema.json> [-o out/]
                  doctruth audit <audit.json> [--json]
                  doctruth doctor [--json]
                  doctruth completion <bash|zsh|fish>
                  doctruth version

                Common:
                  doctruth parse contract.pdf
                  doctruth schema contract.schema.json
                  doctruth extract contract.pdf -s contract.schema.json
                  doctruth doctor
                """;
    }
}
