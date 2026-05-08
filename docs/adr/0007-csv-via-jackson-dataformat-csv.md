# ADR 0007: CSV parsing via jackson-dataformat-csv

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

DocTruth scope includes office formats beyond PDF and DOCX; XLSX and CSV keep
spreadsheet-like source data in the same evidence/citation model as narrative
documents.
CSV is the single most common machine-readable data interchange format in regulated-industry
back offices (financial services, construction tender comparison spreadsheets, healthcare
extracts), so v0.1.0-alpha needs a `CsvDocumentParser` mirroring the shape of
`PdfDocumentParser` / `DocxDocumentParser`.

The candidate Java CSV libraries are:

- **jackson-dataformat-csv** — Jackson module, ~50 KB jar, reuses the Jackson 2.18.2 already
  on our classpath (zero new transitive deps).
- **Apache Commons CSV** — Apache project, well-maintained, ~50 KB but adds its own dep tree.
- **OpenCSV** — popular but heavier; pulls in Apache Commons Lang + (historically) Lombok.
- **univocity-parsers** — fastest in published JMH benchmarks, has auto-delimiter detection
  and other goodies, but the project's licensing has shifted (Apache 2.0 → LGPL → unclear),
  which is a non-starter for an Apache 2.0 licensed library that wants frictionless enterprise
  adoption (ADR 0008).
- **Hand-rolled `Files.lines()` + manual quoting state machine.** Rejected on principle
  per CONTRIBUTING.md §4 ("Build, don't synthesize") — RFC 4180 quoting / embedded newlines /
  CRLF / BOM are exactly the case where a hand-rolled parser silently corrupts edge cases.

## Decision

Use **`com.fasterxml.jackson.dataformat:jackson-dataformat-csv`** at the same
`${jackson.version}` (2.18.2) as the rest of the Jackson stack.

Implementation contract (mirrors `PdfDocumentParser` / `DocxDocumentParser`):

- `CsvDocumentParser.parse(Path)` is the single public entry point.
- One `TableSection` per CSV file (CSV is single-page).
- Empty CSV → zero sections (matches the PDF blank-page rule).
- No header detection (caller decides if row 0 is a header).
- Comma-only delimiter for v0.1.0-alpha.
- Encoding fallback: UTF-8 first, ISO-8859-1 on `MalformedInputException`.

## Consequences

### Why jackson-dataformat-csv wins

- **Zero new transitive deps.** Reuses the Jackson core/databind already on our classpath
  (`jackson-databind` 2.18.2 is the JSON dep for provider call payloads). No new jar tree.
- **Tiny.** ~50 KB; well within the &lt;500 KB total-jar budget per CONTRIBUTING.md "What this
  project IS".
- **Spec-compliant.** RFC 4180 quoting, embedded newlines, CRLF normalisation, optional
  BOM stripping — all already in the library, all production-tested at Jackson scale.
- **Idiomatic for the project.** The codebase is already fluent in Jackson — using its
  CSV module keeps the cognitive surface small (CONTRIBUTING.md §5 "elegance over cleverness").
- **Charset-agnostic.** We control the `Reader` (via `Files.newBufferedReader(path,
  charset)`), so the UTF-8-with-Latin-1-fallback policy fits naturally.

### What jackson-dataformat-csv costs

- **Less feature-rich than univocity.** No auto-delimiter detection (semicolon `;` and tab
  `\t` are common in EU CSV exports). Accepted: comma-only is fine for the
  initial parser; per-call delimiter options can be added when a real fixture
  requires them.
- **Performance is "good enough", not "fastest".** univocity-parsers is faster on synthetic
  benchmarks. We don't care at the v0.1.0 scale (single-document parse on the read path).

### Revisit triggers

1. A user reports a real-world CSV the parser cannot handle for a delimiter / encoding
   reason → add per-call options (delimiter, charset override, header-aware mode)
   while staying on jackson-dataformat-csv.
2. Performance becomes a real bottleneck (multi-MB CSV ingestion path) → re-benchmark
   univocity, but only if the licensing has stabilised.

## Alternatives considered

- **Apache Commons CSV.** Solid choice, but adds a new dep tree (~100 KB) for functionality
  Jackson already covers. Rejected on jar-size grounds.
- **OpenCSV.** Heavier; historical Lombok dependency complicates downstream classpath; not
  on the CONTRIBUTING.md preferred list. Rejected.
- **univocity-parsers.** Fastest, has auto-delimiter detection, but the LGPL/Apache
  licensing history is ambiguous enough to scare enterprise customers. Hard reject for an
  Apache 2.0 library.
- **Hand-rolled.** Rejected per CONTRIBUTING.md §4 — the canonical case for "build, don't
  synthesize". RFC 4180 quoting edge cases are exactly the bugs this principle exists to
  prevent.

## Implementation note

`CsvDocumentParser` shares one `CsvMapper` singleton (Jackson mappers are documented
thread-safe once configured) with `CsvParser.Feature.WRAP_AS_ARRAY` enabled, then reads
each row via `MappingIterator<String[]>` — the idiomatic way to stream a header-less,
arbitrary-width CSV with Jackson. Charset selection lives in the `Reader` (`Files
.newBufferedReader(path, UTF_8)` first, retry with `ISO_8859_1` on `MalformedInputException`)
so the same `CsvMapper` handles both attempts.
