# Memtruth SDK

Memtruth SDK is a Rust middle layer for RAG products. It turns raw corpora into
structured, versioned, model-ready retrieval assets.

It does not replace search engines or app gateways. It sits between source data,
model workers, and retrieval engines.

```text
database / filesystem / R2 / S3 / HTTP corpus
  -> Memtruth contracts
  -> Memtruth chunker
  -> Memtruth projector
  -> Vespa / Postgres FTS / OpenSearch adapters
```

## Crates

- `memtruth-contracts`: `Document`, `Chunk`, stable IDs, validation, and hashes.
- `memtruth-chunker`: parent/passage chunking for long structured documents.
- `memtruth-projector`: nested-schema and section-level FTS projection for user-owned data shapes.
- `memtruth-vespa`: Vespa section feed projection and preflight diagnostics.

## Corpus Coverage And Cleaning Contracts

DocTruth source coverage is explicit and machine-readable:

- `docs/coverage-matrix.json` declares whether a legal source family is
  `supported`, `adjacent`, `planned`, or `unsupported`.
- `docs/corpus-source-registry.v0.1.json` declares the parser owner, source
  kind, canonical URL, and Memtruth requirement for each source family.

Memtruth contracts include `CorpusSource`, `CoverageStatus`, `SourceKind`,
`SourceParserKind`, `LegalCleanText`, and `clean_legal_text()`. These are the
minimum shared contract for bringing a new PRD source into the ingestion path.

`clean_legal_text()` performs conservative legal-text cleanup only: BOM removal,
common HTML entity decoding, non-breaking space normalization, CRLF cleanup,
inline whitespace collapse, and repeated blank-line collapse while preserving
paragraph boundaries. Source-specific parsing still belongs in a parser/fetcher,
but feed projection must pass through Memtruth validation before a source family
can become `supported`.

## FTS Projection

Users often already own a schema in a primary database, filesystem, R2/S3, or a
document store. Memtruth SDK keeps that schema intact and projects a sidecar
retrieval shape.

Example mapping:

```rust
use memtruth_projector::{FtsFieldMapping, FtsMappingSpec};

let spec = FtsMappingSpec {
    mappings: vec![
        FtsFieldMapping::full_text("title", "document.title"),
        FtsFieldMapping::full_text("body", "document.sections.paragraphs.text"),
        FtsFieldMapping::filter("court", "case.court"),
        FtsFieldMapping::metadata("source_key", "storage.r2_key"),
    ],
};
```

Nested arrays are traversed and scalar leaves are flattened into deterministic
field values. This makes FTS compatible with existing source schemas while still
producing clean retrieval assets.

For explainable legal search, use collection projection to turn each structural
item into its own retrieval record:

```rust
use memtruth_projector::{FtsCollectionMappingSpec, FtsFieldMapping};

let spec = FtsCollectionMappingSpec {
    item_path: "document.sections".to_string(),
    mappings: vec![
        FtsFieldMapping::filter("section_number", "number"),
        FtsFieldMapping::filter("section_heading", "heading"),
        FtsFieldMapping::full_text("body", "paragraphs.text"),
    ],
};
```

This preserves user-owned source shape while producing section-level sidecar
records suitable for BM25, fuzzy/prefix lookup, filters, citations, and evidence
display.

## Vespa Section Feed

`memtruth-vespa` includes `LegalSectionDoc` and `legal_section_doc_put()` for
Vespa schemas that store legal text at section level. The projection emits
fields for:

- parent/source identity: `parent_doc_id`, `version_id`;
- section structure: `section_ordinal`, `section_number`, `section_heading`,
  `part_label`, `division_label`, `subdivision_label`;
- FTS and fuzzy/prefix fields: `body_text`, `citation`, `title`,
  `section_tokens`, `heading_tokens`, `citation_tokens`, `title_tokens`.
- optional evidence locators: `source_locator`, `page_start`, `page_end`,
  `page_numbers`, `char_start`, `char_end`, `source_format`, and
  `locator_confidence`.
- optional dense retrieval vector: `dense_embedding_v1_256_native`, a 256D
  L2-normalized Nomic v1.5 Matryoshka vector projected to Vespa
  `tensor<bfloat16>(x[256])`.

For transition compatibility, `memtruth-vespa` can still read an input field
named `dense_embedding`, but generated Vespa feed emits only
`dense_embedding_v1_256_native`.

Sections remain the retrieval and citation unit. Page fields are metadata for
answer evidence and source viewers. If a source such as DOCX/XML has no reliable
page boundary, omit page fields and set or allow `locator_confidence` to become
`unavailable`.

For long legal documents, `memtruth-chunker` creates parent and passage chunks
without crossing `section_path` boundaries. That keeps explanations stable:
answers can cite the Act/case, section, paragraph/page locator, and then expand
to passage evidence without merging adjacent sections into one parent.
Chunk `char_start` and `char_end` values are offsets into the chunker's
normalized text stream, not byte offsets into the raw source file. Preserve raw
source, page, bbox, or byte locators in separate evidence fields.

The workspace also provides a CLI for section JSONL to Vespa feed conversion:

```bash
cargo run -p memtruth-vespa --bin memtruth-legal-section-feed -- \
  --in ../data/generated/privacy-act-1988-c2026c00227.sections.raw.jsonl \
  --out ../data/generated/privacy-act-1988-c2026c00227.sections.vespa-feed.jsonl
```

The CLI accepts DocTruth-style section records with `type`/`date`/`text` fields
and converts them to Vespa `legal_doc` feed operations with the section-level
FTS/fuzzy fields populated.

## Vespa Preflight

`memtruth-vespa` also provides a concise preflight CLI so applications can catch
Vespa feed/schema/query mistakes before a deploy, feed, or runtime query enters
Vespa logs:

```bash
cargo run -p memtruth-vespa --bin memtruth-vespa-preflight -- \
  --sections ../data/generated/privacy-act-1988-c2026c00227.sections.raw.jsonl \
  --sd ../vespa/application/schemas/legal_doc.sd \
  --query /path/to/query.json \
  --report /tmp/memtruth-vespa-preflight.json
```

All flags are optional except that at least one input/output action must be
provided:

- `--sections`: DocTruth-style section JSONL before feed generation.
- `--feed`: final Vespa feed JSONL.
- `--sd`: real Vespa `.sd` schema to compare against the Memtruth preset.
- `--query`: query contract JSON with `ranking`, `filters`, `lanes`,
  `rerank_top_k`, and `total_target_hits`.
- `--report`: writes a JSON report; otherwise the report is printed to stdout.

The command exits `1` when any error diagnostic exists and `2` for CLI/file
usage failures.

The library API exposes the same surface:

```rust
use memtruth_vespa::{
    compare_vespa_schema_spec, legal_section_schema_spec,
    parse_vespa_schema, preflight_legal_section_documents,
    preflight_vespa_query, validate_vespa_schema_spec,
};

let schema = legal_section_schema_spec();
let schema_diagnostics = validate_vespa_schema_spec(&schema);
let actual_schema = parse_vespa_schema(&std::fs::read_to_string("legal_doc.sd")?)?;
let drift = compare_vespa_schema_spec(&schema, &actual_schema);
let report = preflight_legal_section_documents(&sections);
let query_diagnostics = preflight_vespa_query(&schema, &query);

assert!(!report.has_errors());
```

Diagnostics are structured with `code`, `severity`, `path`, `message`, `fix`,
and optional `source_id`. Initial checks cover the section-level legal search
preset:

- BM25 fields must be indexed and have BM25 enabled.
- fast-search fields must be attributes.
- required feed fields must be present and non-empty.
- feed JSON values must match Vespa scalar/array field types.
- dense embeddings must be exactly 256 finite values and approximately
  unit-normalized before feed.
- duplicate section document IDs are reported before they overwrite each other.
- unknown feed fields are warnings until the schema preset is extended.
- real `.sd` files are parsed and compared against the Memtruth preset.
- query contracts are checked for missing rank profiles, invalid filter fields,
  disabled retrieval lanes, and rerank/topK mismatches.

This is the product direction for making Vespa easier to use: Memtruth owns the
projection contract and explicit preflight diagnostics; Vespa `.sd` remains the
schema authority, and Vespa remains the search engine and final deploy/runtime
validator.

## Current Status

This is an initial productized extraction from DocTruth Legal Search and older
Memtruth retrieval ideas. It currently provides only the surfaces with an
immediate retrieval or operation loop: contracts, legal chunking, section-aware
FTS projection, Vespa section feed projection, and Vespa preflight diagnostics.
Model runtime contracts, context-space storage, schema compilers, query planners,
and generic Vespa document/chunk adapters are intentionally out of scope until a
real runtime or schema owns them.
