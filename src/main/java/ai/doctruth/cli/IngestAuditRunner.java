package ai.doctruth.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import ai.doctruth.BlockKind;
import ai.doctruth.ParseException;
import ai.doctruth.PdfDocumentParser;
import ai.doctruth.ParsedDocument;
import ai.doctruth.TextSection;

final class IngestAuditRunner {

    private static final int LOW_TEXT_CHARS = 50;
    private static final int OVERSIZED_BLOCK_CHARS = 1_800;
    private static final int OVERSIZED_BLOCK_LINES = 18;

    IngestAuditReport run(Path root, int limit) throws CliException {
        if (!Files.isDirectory(root)) {
            throw new CliException("ingest audit root is not a directory: " + root);
        }
        var files = listPdfs(root, limit);
        var results = new ArrayList<IngestAuditFileResult>();
        var issueSummary = new LinkedHashMap<String, Integer>();
        int parsed = 0;
        int failed = 0;
        for (var file : files) {
            var result = auditFile(file);
            results.add(result);
            if ("parsed".equals(result.status())) {
                parsed++;
            } else {
                failed++;
            }
            result.findings().forEach(finding -> increment(issueSummary, finding.category()));
        }
        seedIssueSummary(issueSummary);
        return new IngestAuditReport(root, files.size(), parsed, failed, issueSummary, List.copyOf(results));
    }

    private static List<Path> listPdfs(Path root, int limit) throws CliException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(IngestAuditRunner::isPdf)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            throw new CliException("failed to list PDFs: " + e.getMessage(), e);
        }
    }

    private static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static IngestAuditFileResult auditFile(Path file) {
        try {
            return parsedFile(file, PdfDocumentParser.parse(file));
        } catch (ParseException e) {
            var finding = new IngestAuditFinding("doctruth_parse", e.errorCode(), 1, 0);
            return new IngestAuditFileResult(
                    file.getFileName().toString(),
                    "parse_failed",
                    e.errorCode(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Map.of(),
                    List.of(finding));
        }
    }

    private static IngestAuditFileResult parsedFile(Path file, ParsedDocument doc) {
        var textSections = doc.sections().stream().filter(TextSection.class::isInstance).map(TextSection.class::cast).toList();
        var findings = new ArrayList<IngestAuditFinding>();
        int textChars = textSections.stream().mapToInt(section -> section.text().length()).sum();
        int textWithBbox = (int) textSections.stream().filter(section -> section.boundingBox().isPresent()).count();
        int maxBlockChars = textSections.stream().mapToInt(section -> section.text().length()).max().orElse(0);
        int maxBlockLines = textSections.stream().mapToInt(section -> (int) section.text().lines().count()).max().orElse(0);
        var kindCounts = kindCounts(textSections);
        addFindings(findings, textSections, textChars, textWithBbox, maxBlockChars, maxBlockLines, kindCounts);
        return new IngestAuditFileResult(
                file.getFileName().toString(),
                "parsed",
                "",
                doc.metadata().pageCount(),
                doc.sections().size(),
                textSections.size(),
                textChars,
                textWithBbox,
                maxBlockChars,
                maxBlockLines,
                kindCounts,
                List.copyOf(findings));
    }

    private static Map<String, Integer> kindCounts(List<TextSection> sections) {
        var counts = new EnumMap<BlockKind, Integer>(BlockKind.class);
        for (var section : sections) {
            counts.merge(section.kind(), 1, Integer::sum);
        }
        var out = new LinkedHashMap<String, Integer>();
        for (var kind : BlockKind.values()) {
            out.put(kind.name(), counts.getOrDefault(kind, 0));
        }
        return out;
    }

    private static void addFindings(
            List<IngestAuditFinding> out,
            List<TextSection> textSections,
            int textChars,
            int textWithBbox,
            int maxBlockChars,
            int maxBlockLines,
            Map<String, Integer> kindCounts) {
        if (textChars < LOW_TEXT_CHARS || textSections.isEmpty()) {
            out.add(new IngestAuditFinding("doctruth_text", "low_text_layer_chars", textChars, LOW_TEXT_CHARS));
        }
        if (textWithBbox < textSections.size()) {
            out.add(new IngestAuditFinding("evidence_mapping", "missing_text_bboxes", textWithBbox, textSections.size()));
        }
        if (maxBlockChars > OVERSIZED_BLOCK_CHARS) {
            out.add(new IngestAuditFinding("doctruth_segmentation", "oversized_text_block_chars", maxBlockChars, OVERSIZED_BLOCK_CHARS));
        }
        if (maxBlockLines > OVERSIZED_BLOCK_LINES) {
            out.add(new IngestAuditFinding("doctruth_segmentation", "oversized_text_block_lines", maxBlockLines, OVERSIZED_BLOCK_LINES));
        }
        if (!textSections.isEmpty() && kindCounts.getOrDefault(BlockKind.HEADING.name(), 0) == 0) {
            out.add(new IngestAuditFinding("block_labeling", "no_heading_blocks", 0, 1));
        }
    }

    private static void seedIssueSummary(Map<String, Integer> summary) {
        for (var key : List.of("doctruth_text", "doctruth_segmentation", "block_labeling", "context_pack", "evidence_mapping", "doctruth_parse")) {
            summary.putIfAbsent(key, 0);
        }
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }
}
