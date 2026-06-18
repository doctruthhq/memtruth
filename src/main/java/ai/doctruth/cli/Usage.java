package ai.doctruth.cli;

final class Usage {

    private Usage() {
        throw new AssertionError("no instances");
    }

    static String main() {
        return """
                DocTruth - Rust-core document evidence runtime

                Usage:
                  doctruth init
                  doctruth parse <document> [--json|--markdown|--format <format>] [--profile <profile>] [--preset <preset>] [--backend auto|pdfbox|sidecar] [--runtime <path>] [--source-map] [--bboxes] [-o parsed.out]
                  doctruth render-pages <document> -o <dir>
                  doctruth review-package <document> [--preset <preset>] -o <dir>
                  doctruth ingest-audit <pdf-dir> [--json] [--limit N] [-o audit.json]
                  doctruth benchmark-corpus <manifest.json> [--json] [--offline] [--report-out <report.json>]
                  doctruth benchmark-oracle --engine opendataloader-hybrid <document> [--json|--format <format>]
                  doctruth cache warm <manifest.json> --preset <preset> [--cache <dir>] [--offline] [--json]
                  doctruth schema <schema.json> [--json]
                  doctruth extract <document> -s <schema.json> [-o out/]
                  doctruth audit <audit.json> [--json]
                  doctruth verify-audit <trust-document.json> <audit.json>
                  doctruth verify-source-map <rendered> <map.json> [--source <document>]
                  doctruth verify-benchmark-report <report.json>
                  doctruth mcp
                  doctruth doctor [--json]
                  doctruth doctor models
                  doctruth completion <bash|zsh|fish>
                  doctruth version

                Common:
                  doctruth parse contract.pdf
                  doctruth parse resume.pdf --format markdown --profile clean --source-map -o resume.md
                  doctruth parse resume.pdf --format content_blocks -o resume.content_blocks.json
                  doctruth parse resume.pdf --format parse_trace -o resume.parse_trace.json
                  doctruth parse resume.pdf --format plain -o resume.txt
                  doctruth parse resume.pdf --runtime ./doctruth-runtime --preset standard --format json
                  doctruth render-pages resume.pdf -o .doctruth/pages/resume
                  doctruth review-package resume.pdf --preset ocr -o .doctruth/reviews/resume
                  doctruth ingest-audit ./resumes --json -o ingest-audit.json
                  doctruth benchmark-corpus parser-corpus.json --json
                  doctruth benchmark-corpus parser-corpus.json --json --report-out parser-report.json
                  doctruth benchmark-corpus parser-corpus.json --offline
                  doctruth benchmark-oracle --engine opendataloader-hybrid resume.pdf --json
                  doctruth benchmark-oracle --engine opendataloader-hybrid resume.pdf --format content_blocks
                  doctruth cache warm models.json --preset table-lite --cache .doctruth/models --json
                  doctruth schema contract.schema.json
                  doctruth extract contract.pdf -s contract.schema.json
                  doctruth verify-audit trust-document.json audit.json
                  doctruth verify-source-map resume.md resume.doctruth-map.json --source resume.pdf
                  doctruth verify-benchmark-report parser-report.json
                  doctruth mcp
                  doctruth doctor
                  doctruth doctor models
                """;
    }
}
