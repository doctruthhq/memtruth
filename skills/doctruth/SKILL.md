---
name: doctruth
description: Use when an agent needs evidence-backed document parsing, citation verification, bbox/source-map output, or local MCP access to DocTruth document evidence tools.
---

# DocTruth

Use this skill when a task needs document evidence that can be replayed:
PDF/DOCX/XLSX/CSV parsing, compact LLM context, bbox-backed citations, table
cells, source maps, audit JSON, or citation verification.

## Local MCP Bootstrap

If DocTruth is available as a CLI, start the local stdio MCP server with:

```bash
doctruth mcp
```

To generate a local MCP config snippet, run:

```bash
skills/doctruth/scripts/bootstrap-local-mcp.sh --command doctruth --print-json
```

Use `--out <path>` to write the config to disk.

## MCP Tools

Prefer MCP tools over ad hoc text extraction:

```text
doctruth.parse_document
doctruth.get_layout_regions
doctruth.get_table_cells
doctruth.get_evidence_span
doctruth.verify_citation
doctruth.warm_model_cache
```

Use `doctruth.parse_document` when the agent needs compact context plus
source-map/evidence payloads. Use the narrower tools when the agent already
has a document path and needs only layout regions, table cells, one evidence
span, or quote verification. Use `doctruth.warm_model_cache` as a local
preflight before model-assisted parsing; it verifies expected model artifacts
without downloading them.

## Ground Rules

- Treat `structuredContent` as the source of truth for replayable evidence.
- Do not claim a value came from a document unless a returned evidence span or
  verified citation supports it.
- For scanned or weak OCR documents, inspect `auditGradeStatus`, parser
  warnings, and confidence before using the text as audit-grade evidence.
- Preserve `sourceHash`, `evidenceSpanId`, `unitId`, page, and bbox when
  passing DocTruth evidence into memory, replay, or audit systems.
