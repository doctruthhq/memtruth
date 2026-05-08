# Pydantic Interop

This example shows the migration path for a team that already owns Pydantic v2
models but wants Java services to perform auditable extraction without a Python
runtime dependency.

## Flow

1. Export a Pydantic model to JSON Schema.
2. Optionally check it with the DocTruth CLI.
3. Load it with `JsonSchema.from(Path)`.
4. Run `extractJson(...)` with required citations.
5. Write `result.toAuditJson(...)` for review or downstream ingestion.

## Export or Check the Schema

If your Python model is importable as `myapp.schemas:ResumeExtraction`:

```bash
java -jar target/doctruth-java-0.1.0-SNAPSHOT.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  --out examples/pydantic-interop/resume.schema.json \
  --check
```

The command invokes Python only during migration. Production Java extraction
uses the exported JSON Schema file and the DocTruth jar.

This directory includes a representative `resume.schema.json` with nested
`$defs` / `$ref`, nullable `anyOf`, string patterns, arrays, and numeric
constraints.

## Run the Java Example

Build the jar and classpath:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  mvn -B -ntp package -DskipTests
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/doctruth-cp.txt
CP="target/doctruth-java-0.1.0-SNAPSHOT.jar:$(cat /tmp/doctruth-cp.txt)"
```

Compile and run:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
javac -cp "$CP" -d /tmp/pydantic-interop-build \
  examples/pydantic-interop/PydanticInteropExample.java
java -cp "/tmp/pydantic-interop-build:$CP" \
  ai.doctruth.examples.pydanticinterop.PydanticInteropExample \
  fixtures/pdf/004b85e6-b2dd-4d7f-aebc-5e0bab06e1f3.pdf \
  examples/pydantic-interop/resume.schema.json \
  /tmp/doctruth-resume-audit.json
```

## Failure Handling

- `EXTRACTION_SCHEMA_VALIDATION_FAILED` means the model returned JSON that did
  not satisfy the full schema.
- `EXTRACTION_EVIDENCE_MISSING` means a required field could not be matched
  strongly enough to source text.
- Provider request schemas may be normalized per provider, but local validation
  always uses the original schema.

See [`../../docs/error-handling.md`](../../docs/error-handling.md) for the full
integration matrix.
