# ADR 0005: Apache Commons Text for fuzzy citation matching

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

The library's core differentiator (per CONTRIBUTING.md "Engineering principles" §2) is
**non-silent citation matching**. A prior production pattern used a short substring prefix match that
returned `page=None` when no match was found — silent failure that compliance teams had to
discover by inspection.

DocTruth matches every extracted field back to its source span via:

1. **Exact substring** (fast path, `matchScore = 1.0`).
2. **Fuzzy similarity** (slow path, `matchScore` from a similarity metric).
3. **Below-threshold warning** (when even the best fuzzy match is poor, emit SLF4J `warn`
   and surface a low `matchScore` in `Citation` — never silent).

Step 2 needs a similarity metric. The candidates are:

- Levenshtein distance (edit distance).
- Jaro–Winkler similarity (good on short proper names, OCR'd text).
- Cosine similarity of n-gram vectors.
- Embedding-based semantic similarity (e.g. via Sentence-BERT).

## Decision

Use **Apache Commons Text** (`org.apache.commons:commons-text`) as the source of fuzzy
similarity primitives. Specifically:

- `org.apache.commons.text.similarity.JaroWinklerSimilarity` for primary scoring (handles
  capitalisation drift and trailing-character mismatches well — common in OCR and copy-paste
  edge cases).
- `org.apache.commons.text.similarity.LevenshteinDistance` available for exact edit-distance
  use cases (e.g. typo correction in audit reports).

## Consequences

### Why Commons Text wins

- **CONTRIBUTING.md "Engineering principles" §4** explicitly lists it: *"String similarity / fuzzy
  match → Apache Commons Text"*. Using it is consistent with the project's codified default.
- **Single small jar** (~250 KB) plus one well-known transitive dep (commons-lang3, ~700 KB,
  also already common in enterprise classpaths).
- **Tested at scale.** Commons Text similarity classes are used in millions of Apache
  projects; we don't have to re-validate Jaro–Winkler edge cases.
- **Multiple metrics in one jar.** If we later need Levenshtein for typo correction or
  cosine-of-n-grams for longer phrases, no new dep needed.
- **No model artefacts.** Embedding-based matching would require shipping or loading model
  weights (~100 MB+). Out of scope for a single-jar primitive library.

### What Commons Text costs

- **Two transitive deps** (commons-text + commons-lang3) added to the user's classpath.
  Mitigation: both are widely-deployed, low-conflict-risk libraries.
- **Pure-string matching ceiling.** Cannot disambiguate semantically-equivalent rewrites
  ("January 5th" vs "Jan 5"). Mitigation: caller surfaces low `matchScore`; if the user
  needs semantic matching, they layer pgvector / Qdrant on top.

### Revisit triggers

1. A regulated-industry user requires semantic citation matching (e.g. clinical-note
   extraction where the LLM might paraphrase). At that point evaluate adding an optional
   embedding-based matcher behind a `CitationMatcher` SPI — Commons Text remains the
   default, semantic becomes opt-in.
2. Commons Text similarity classes go unmaintained for > 12 months.

## Alternatives considered

- **Hand-rolled Levenshtein DP.** ~30 LOC, looks easy. Rejected per CONTRIBUTING.md §4 "Build,
  don't synthesize" — the canonical case the principle was written for. Hand-rolled
  similarity has subtle bugs in normalisation, Unicode handling, and locale-aware
  case-folding (e.g. Turkish dotted/dotless I) that Commons Text already handles.
- **JDK-only via `String.equals` + `.contains`.** Inadequate — short-prefix
  matching is the anti-pattern this library exists to replace. Rejected.
- **Apache Lucene's `LevenshteinAutomata`.** Powerful but Lucene is a 4 MB dep tree (we'd
  pull `lucene-core`); over-spec for a string-similarity feature.
- **JaroWinkler from a single-purpose lib (e.g. `info.debatty:java-string-similarity`).**
  Smaller (~100 KB) but obscure, single-maintainer, and not on the CONTRIBUTING.md preferred list.
  Rejected for ecosystem-trust reasons.
- **Embedding-based semantic similarity (Sentence-BERT, OpenAI embeddings).** Requires ML
  model artefacts or a network call per match — both out of scope for a primitive library
  meant to drop into any Java app.

## Implementation note

Citation matching is centralised in `ai.doctruth.internal.citation.CitationMatcher`
(target ≤ 100 LOC). It exposes a single method:

```java
Optional<Citation> match(String fieldValue, ParsedDocument doc, double minScore);
```

The default `minScore` is `0.85` (Jaro–Winkler scale where 1.0 is identical, 0.0 is
completely different). Below the threshold, the matcher returns `Optional.empty()` and
the caller emits an SLF4J `warn` event tagging the offending field path.

The Commons Text dependency is NOT exposed through the public API — only `Citation`
records. Per CONTRIBUTING.md "Engineering principles" §1, internal types must not leak.
