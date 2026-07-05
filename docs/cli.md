# CLI

DocTruth CLI is the try/debug/inspect entry point. The primary integration path
is the Java SDK (`DocTruth.withOpenAi(...).fromPdf(...).extract(...).run()`),
while the CLI is optimized for first-run evidence inspection: parse without an
LLM key, check schemas directly, and write extraction outputs into a run
directory.

Build the standalone CLI jar:

```bash
mvn package -DskipTests
```

Run it:

```bash
java -jar target/doctruth-java-0.2.0-alpha-all.jar --help
```

Install a local launcher:

```bash
scripts/install-cli.sh --prefix "$HOME/.local"
export PATH="$HOME/.local/bin:$PATH"
doctruth version
```

See [Install DocTruth CLI](install.md) for the install path.

## Commands

### Initialize

```bash
doctruth init
```

Creates:

```text
doctruth.yml
schemas/
.doctruth/runs/
```

`doctruth.yml` stores defaults for provider, model, and output directory.

### Parse

No provider key required:

```bash
doctruth parse contract.pdf
```

Prints a summary:

```text
contract.pdf
parser: opendataloader
pages: 3
sections: 42
text: 38
tables: 2
figures: 0
bbox coverage: 31/38
```

PDF parsing defaults to the OpenDataLoader backend. Use the legacy PDFBox
backend only for compatibility checks or parser debugging:

```bash
doctruth parse contract.pdf --parser pdfbox
```

Write DocTruth's audit-ready TrustDocument JSON:

```bash
doctruth parse contract.pdf --format json -o trust-document.json
```

The output includes the source SHA-256, page count, parser backend, ordered
content units, page/line anchors, tables, and optional PDF bounding boxes.

Write the lower-level parsed section JSON for parser debugging:

```bash
doctruth parse contract.pdf --json -o parsed.json
```

Show that bbox recovery is enabled in the summary:

```bash
doctruth parse contract.pdf --bboxes
```

### Profile

Measure local parser latency and heap movement without an LLM key:

```bash
doctruth profile contract.pdf --iterations 3 --json
```

Include output serialization cost when comparing parser plus JSON rendering:

```bash
doctruth profile contract.pdf --iterations 3 --include-output trust-json --json
```

Measure the streaming file-writer path used by `parse -o`:

```bash
doctruth profile contract.pdf --iterations 3 --include-output trust-json-file --json
```

The JSON includes:

```text
parser
iterations
fileSizeBytes
sectionCount
includeOutput
parseLatencyMillis
outputLatencyMillis
coldLatencyMillis
warmAverageLatencyMillis
coldOutputLatencyMillis
warmAverageOutputLatencyMillis
profiledOutputChars
profiledOutputBytes
heapUsedBeforeBytes
heapUsedAfterBytes
heapDeltaBytes
```

`trust-json` and `parsed-json` measure string rendering. `trust-json-file` and
`parsed-json-file` measure temporary file writer output and report bytes.
Warm latency is a subsequent full parse in the same JVM, not a persistent
parser-worker residency metric.

Use this command to compare `opendataloader` and `pdfbox` on a named corpus
before proposing Rust, Go, or other native optimization work.

### Schema

Check a JSON Schema:

```bash
doctruth schema contract.schema.json
```

Machine-readable summary:

```bash
doctruth schema contract.schema.json --json
```

### Extract

Default extraction:

```bash
doctruth extract contract.pdf -s contract.schema.json
```

By default, DocTruth:

- reads provider/model/output defaults from `doctruth.yml` when present
- uses `openai` as the default provider
- requires citations for schema leaf fields
- writes `trust-document.json`, `result.json`, `audit.json`, and `manifest.json`
  to `.doctruth/runs/<run-id>/`

Common overrides:

```bash
doctruth extract contract.pdf -s contract.schema.json -o out/
doctruth extract contract.pdf -s contract.schema.json --provider anthropic
doctruth extract contract.pdf -s contract.schema.json --model gpt-4o-mini
doctruth extract contract.pdf -s contract.schema.json --base-url http://localhost:11434/v1
doctruth extract contract.pdf -s contract.schema.json --allow-uncited
doctruth extract contract.pdf -s contract.schema.json --require partyA,totalValue
doctruth extract contract.pdf -s contract.schema.json --evidence-first
```

`--evidence-first` wraps each schema leaf as `{ "value": ..., "exactQuote": ... }`
for the provider response. DocTruth unwraps `value` into `result.json`, validates
it against the original schema, and uses `exactQuote` for citation matching.

`audit.json` derivations include the TrustDocument `docId` and `unit` id when a
field citation maps to a parsed unit.

Provider keys:

| Provider | Env var |
| --- | --- |
| `openai` | `OPENAI_API_KEY` |
| `anthropic` | `ANTHROPIC_API_KEY` |
| `gemini` | `GOOGLE_API_KEY` |
| `deepseek` | `DEEPSEEK_API_KEY` |

### Audit

Read an audit JSON file:

```bash
doctruth audit .doctruth/runs/run_abc/audit.json
```

Machine-readable summary:

```bash
doctruth audit .doctruth/runs/run_abc/audit.json --json
```

### Doctor

Check the local runtime, project config, output directory, and provider-key
readiness:

```bash
doctruth doctor
doctruth doctor --json
```

`doctor` does not call an LLM. It is safe to run before configuring extraction.

### Completion

Generate shell completion:

```bash
doctruth completion bash > ~/.local/share/bash-completion/completions/doctruth
doctruth completion zsh > "${fpath[1]}/_doctruth"
doctruth completion fish > ~/.config/fish/completions/doctruth.fish
```

### Version

```bash
doctruth version
doctruth --version
```

## Advanced: Pydantic Schema Migration

This is not the primary path. Use it only when a team already owns Pydantic v2
models and wants to export JSON Schema at build time.

Export a Pydantic v2 model to JSON Schema:

```bash
doctruth migrate pydantic myapp.schemas:Resume -o resume.schema.json --check
```

This command may invoke Python during migration. Runtime Java extraction only
uses the exported schema file.

## Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Command succeeded |
| `1` | Runtime failure, parse failure, provider failure, or schema compatibility failure |
| `2` | Invalid CLI usage |
