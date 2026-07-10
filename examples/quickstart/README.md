# DocTruth quickstart

The simplest possible run: parse a PDF, extract a typed `Contract` record from
it via an OpenAI-compatible endpoint, and emit a per-field audit log. One Java file, no Maven
submodule, no framework.

## What this does

1. Generates a tiny in-memory PDF (or reads `args[0]` if you pass a path).
2. Calls `DocTruth.withOpenAi(key).fromPdf(...).extract(...).withEvidence().run()`.
3. Prints the extracted value, the per-field citations, the per-field confidence map size, the run provenance, and writes an `audit.json` next to the PDF.

## Run it

You need: Java 25+, an `OPENAI_API_KEY`, and the DocTruth jar on the
classpath. There are two equally-valid ways to invoke it.

### Path A — via Maven `exec:java` in your own project

Drop `Quickstart.java` into `src/main/java/ai/doctruth/examples/quickstart/`
of any Java 25 Maven project that already depends on `doctruth-java`. Add
this to your `pom.xml` once:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.5.0</version>
</plugin>
```

Then run:

```bash
export OPENAI_API_KEY=sk-...
mvn exec:java \
  -Dexec.mainClass="ai.doctruth.examples.quickstart.Quickstart" \
  -Dexec.args="path/to/contract.pdf"   # optional; omit for the bundled sample PDF
```

### Path B — plain `javac` + `java`

```bash
export OPENAI_API_KEY=sk-...
mvn -q -f /path/to/memtruth/java/pom.xml package -DskipTests
mvn -q -f /path/to/memtruth/java/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/doctruth-cp.txt
CP="/path/to/memtruth/java/target/doctruth-java-0.2.0-alpha.jar:$(cat /tmp/doctruth-cp.txt)"
javac -cp "$CP" -d build /path/to/memtruth/examples/quickstart/Quickstart.java
java  -cp "build:$CP" ai.doctruth.examples.quickstart.Quickstart
```

## Expected output

```
Source PDF: /tmp/doctruth-quickstart-3417829.pdf

Extracted value:
  Contract[partyA=Acme Industrial Materials Pty Ltd, partyB=BetaCorp Construction Ltd, effectiveDate=2026-04-01, totalValue=2450000]

Citations: 4 field(s)
  first: partyA -> page 1 line 2  matchScore=1.00
  bbox: BoundingBox[x0=72.4, y0=118.0, x1=380.7, y1=142.5]   # present when PDF geometry is available

Confidence: 4 field(s)

Provenance:
  model=openai modelVersion=gpt-4o
  extractedAt=2026-05-07T05:30:14.218Z
  sourcePublishedAt=2026-01-01T00:00:00Z

Audit JSON written to: /tmp/audit.json
```

(Full capture in [`sample-output.txt`](./sample-output.txt).)

## Provider switch

Change one line — the rest of the pipeline is provider-agnostic:

```java
// OpenAI / OpenAI-compatible (this quickstart)
DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))

// OpenAI-compatible endpoint with an explicit model
DocTruth.withProvider(LlmProviders.openAiCompatible(
        System.getenv("OPENAI_API_KEY"),
        URI.create("https://api.openai.com/v1/chat/completions"),
        "gpt-4o"))

// Anthropic
DocTruth.withProvider(LlmProviders.anthropic(System.getenv("ANTHROPIC_API_KEY")))

// Gemini
DocTruth.withProvider(LlmProviders.gemini(System.getenv("GOOGLE_API_KEY")))

// DeepSeek
DocTruth.withProvider(LlmProviders.deepSeek(System.getenv("DEEPSEEK_API_KEY")))
```

## Audit log

`result.toAuditJson()` returns a W3C PROV-O compatible JSON-LD document — the
shape compliance teams already know how to ingest. Compact example:

```json
{
  "@context": "https://www.w3.org/ns/prov",
  "@type": "prov:Entity",
  "doctruth:value": {
    "partyA": "Acme Industrial Materials Pty Ltd"
  },
  "doctruth:retries": 0,
  "prov:wasGeneratedBy": {
    "@type": "prov:Activity",
    "prov:startedAtTime": "2026-05-07T05:30:14.218Z",
    "prov:wasAssociatedWith": {
      "@type": "prov:SoftwareAgent",
      "rdfs:label": "openai",
      "prov:version": "gpt-4o"
    }
  },
  "prov:wasDerivedFrom": [
    {
      "@type": "prov:Entity",
      "doctruth:fieldPath": "partyA",
      "prov:value": "Acme Industrial Materials Pty Ltd",
      "doctruth:matchScore": 1.0,
      "doctruth:sourceLocation": {
        "pageStart": 1,
        "pageEnd": 1,
        "lineStart": 2,
        "lineEnd": 2,
        "charOffset": 31
      },
      "doctruth:boundingBox": {
        "x0": 72.4,
        "y0": 118.0,
        "x1": 380.7,
        "y1": 142.5
      }
    }
  ]
}
```

See [the evidence schema](../../docs/evidence-schema.md) for the full contract.
