# JSON Schema Integration

DocTruth supports Java records and classes as the native schema path. It also
accepts caller-supplied JSON Schema for teams that define extraction contracts
outside Java.

For normal Java application code, prefer the SDK-first record path:
`DocTruth.withOpenAi(...).fromPdf(...).extract(...).withEvidence().run()`.
Use JSON Schema when the schema is owned outside Java or must stay
language-neutral.

## Load A Schema

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("contract.pdf"))
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withEvidence()
        .runJson();
```

The returned value is a Jackson `JsonNode`, while citations, confidence, and
provenance stay in the normal `ExtractionResult` contract.

## Common Schema Sources

JSON Schema is useful when:

- another service already owns the schema
- a Pydantic model exports the contract at build time
- a document automation team wants language-neutral templates
- the extraction target changes more often than Java code

DocTruth supports common Pydantic v2 JSON Schema exports, including local
`$defs` / `$ref`, nullable unions, nested objects, arrays, enums, required
fields, scalar constraints, and `additionalProperties=false`.

## Citation Requirements

Use `requireCitation(...)` for fields that must not enter the result without
source evidence:

```java
var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("invoice.pdf"))
        .extractJson("Extract invoice fields", schema)
        .requireCitation("invoiceNumber")
        .requireCitation("totalAmount")
        .withMaxRetries(2)
        .runJson();
```

This keeps schema validation and source evidence in the same extraction run.

## Build-Time Pydantic Export

If schemas start in Python, export JSON Schema at build time and feed the schema
file to the Java runtime:

```bash
java -jar java/target/doctruth-java-0.2.0-alpha-all.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  -o schemas/resume.schema.json \
  --check
```

DocTruth does not import Python in Java production. Pydantic compatibility means
JSON Schema interoperability.
