# DocTruth quickstart

The simplest possible run: parse a PDF, extract a typed `Contract` record from
it via an OpenAI-compatible endpoint, and emit a per-field audit log. One Java file, no Maven
submodule, no framework.

## What this does

1. Generates a tiny in-memory PDF (or reads `args[0]` if you pass a path).
2. Calls `DocTruth.from(new OpenAiProvider(key)).extract(...).withProvenance().withSourcePublishedAt(...).withBitemporal().withConfidence().run(doc)`.
3. Prints the extracted value, the per-field citations, the per-field confidence map size, the run provenance, and writes a `audit.json` next to the PDF.

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
mvn -q -f /path/to/doctruth-java/pom.xml package -DskipTests
mvn -q -f /path/to/doctruth-java/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/doctruth-cp.txt
CP="/path/to/doctruth-java/target/doctruth-java-0.1.0-SNAPSHOT.jar:$(cat /tmp/doctruth-cp.txt)"
javac -cp "$CP" -d build /path/to/doctruth-java/examples/quickstart/Quickstart.java
java  -cp "build:$CP" ai.doctruth.examples.quickstart.Quickstart
```

## Expected output

```
Source PDF: /tmp/doctruth-quickstart-3417829.pdf
Parsed 1 page(s) from doctruth-quickstart-3417829.pdf

Extracted value:
  Contract[partyA=Acme Industrial Materials Pty Ltd, partyB=BetaCorp Construction Ltd, effectiveDate=2026-04-01, totalValue=2450000]

Citations: 4 field(s)
  first: partyA -> page 1 line 2  matchScore=1.00

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
DocTruth.from(new OpenAiProvider(System.getenv("OPENAI_API_KEY")))

// OpenAI-compatible endpoint with an explicit model
DocTruth.from(new OpenAiProvider(
        System.getenv("OPENAI_API_KEY"),
        URI.create("https://api.openai.com/v1/chat/completions"),
        "gpt-4o"))

// Anthropic
DocTruth.from(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))

// Gemini
DocTruth.from(new GeminiProvider(System.getenv("GOOGLE_API_KEY")))

// DeepSeek
DocTruth.from(new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY")))
```

## Audit log

`result.toAuditJson()` returns a W3C PROV-O compatible JSON-LD document — the
shape compliance teams already know how to ingest. Compact example:

```json
{
  "@context": "https://www.w3.org/ns/prov",
  "@type": "prov:Entity",
  "prov:wasGeneratedBy": {
    "@type": "prov:Activity",
    "model": "openai",
    "modelVersion": "gpt-4o",
    "extractedAt": "2026-05-07T05:30:14.218Z"
  },
  "citations": {
    "partyA": { "page": 1, "line": 2, "exactQuote": "Acme Industrial Materials Pty Ltd", "matchScore": 1.0 }
  }
}
```
