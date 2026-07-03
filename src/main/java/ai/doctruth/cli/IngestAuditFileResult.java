package ai.doctruth.cli;

import java.util.List;
import java.util.Map;

record IngestAuditFileResult(
        String filename,
        String status,
        String errorCode,
        int pages,
        int sections,
        int textSections,
        int textChars,
        int textWithBbox,
        int maxBlockChars,
        int maxBlockLines,
        Map<String, Integer> kindCounts,
        List<IngestAuditFinding> findings) {}
