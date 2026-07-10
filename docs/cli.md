# CLI

DocTruth CLI is the try/debug/inspect entry point. The primary integration path
is the Java SDK (`DocTruth.withOpenAi(...).fromPdf(...).extract(...).run()`),
while the CLI is optimized for first-run evidence inspection: parse without an
LLM key, check schemas directly, and write extraction outputs into a run
directory.

Build the standalone CLI jar:

```bash
mvn -f java/pom.xml package -DskipTests
```

Run it:

```bash
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar --help
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

Write parsed sections as JSON:

```bash
doctruth parse contract.pdf --json -o parsed.json
```

Show that bbox recovery is enabled in the summary:

```bash
doctruth parse contract.pdf --bboxes
```

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
- requires citations for top-level schema fields
- writes `result.json` and `audit.json` to `.doctruth/runs/<run-id>/`

Common overrides:

```bash
doctruth extract contract.pdf -s contract.schema.json -o out/
doctruth extract contract.pdf -s contract.schema.json --provider anthropic
doctruth extract contract.pdf -s contract.schema.json --model gpt-4o-mini
doctruth extract contract.pdf -s contract.schema.json --base-url http://localhost:11434/v1
doctruth extract contract.pdf -s contract.schema.json --allow-uncited
doctruth extract contract.pdf -s contract.schema.json --require partyA,totalValue
```

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
