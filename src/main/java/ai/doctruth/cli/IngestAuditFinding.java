package ai.doctruth.cli;

record IngestAuditFinding(String category, String reason, int value, int threshold) {}
