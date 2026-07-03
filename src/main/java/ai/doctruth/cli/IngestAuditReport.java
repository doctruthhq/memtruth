package ai.doctruth.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record IngestAuditReport(
        Path root,
        int totalFiles,
        int parsed,
        int failed,
        Map<String, Integer> issueSummary,
        List<IngestAuditFileResult> files) {}
