# Java Integration Guide

DocTruth is designed as a Java backend primitive. It should fit into an existing
service without forcing a framework, agent runtime, or vendor SDK onto the
application.

## Integration Model

The normal integration path is:

```text
source document
→ DocTruth parser
→ ParsedDocument
→ DocTruth extraction
→ typed value + citations + confidence + provenance
→ caller's business system
```

DocTruth owns the extraction evidence boundary. The application still owns
authentication, storage, queues, review workflow, and business-specific policy.

## Plain Java

Use plain Java when you want the smallest possible integration surface.

```java
import ai.doctruth.DocTruth;
import ai.doctruth.ExtractionResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

ExtractionResult<Contract> result = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract contract terms", Contract.class)
        .withEvidence()
        .run();

var value = result.value();
var citation = result.requireCitation("totalValue");
result.writeAudit(Path.of("audit.json"));
```

The returned value is ordinary Java. The evidence map is ordinary Java. No
framework type is required.

## Spring Boot

DocTruth does not depend on Spring, but it works naturally inside Spring
services.

Example service shape:

```java
import ai.doctruth.DocTruth;
import ai.doctruth.ExtractionException;
import ai.doctruth.ExtractionResult;
import ai.doctruth.LlmProvider;
import ai.doctruth.ParseException;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public final class ContractExtractionService {

    private final LlmProvider provider;

    public ContractExtractionService(LlmProvider provider) {
        this.provider = provider;
    }

    public ExtractionResult<Contract> extract(Path pdf)
            throws ParseException, ExtractionException {
        return DocTruth.withProvider(provider)
                .fromPdf(pdf)
                .extract("Extract contract terms", Contract.class)
                .withEvidence()
                .run();
    }
}
```

Provider configuration can stay in the application:

```java
import ai.doctruth.LlmProvider;
import ai.doctruth.LlmProviders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DocTruthConfig {

    @Bean
    LlmProvider llmProvider() {
        return LlmProviders.openAi(System.getenv("OPENAI_API_KEY"));
    }
}
```

Recommended Spring boundary:

- Controller receives or locates the document.
- Service calls DocTruth.
- Repository stores `result.value()` and `result.toAuditJson()`.
- Review UI reads `result.citations()` or the stored audit JSON.

## Quarkus / Micronaut

The same pattern applies in Quarkus, Micronaut, Helidon, or plain Jakarta
services:

- create one provider bean / singleton
- call `DocTruth.withProvider(provider).fromPdf(...)` inside an application service
- store typed value and audit JSON separately

Keep DocTruth inside a service boundary rather than scattering extraction calls
across controllers and jobs.

## LangChain4j Interop

DocTruth is not a LangChain4j replacement. Use LangChain4j for broader
orchestration if you already use it, and use DocTruth at the evidence-gated
extraction boundary.

Recommended split:

| Responsibility | Owner |
| --- | --- |
| Agent orchestration | LangChain4j |
| Retrieval / tools / memory | LangChain4j or application |
| Document parsing to source-located sections | DocTruth |
| Schema-bound extraction | DocTruth |
| Field citation matching | DocTruth |
| Audit JSON | DocTruth |

Typical flow:

```text
LangChain4j workflow chooses document and schema
→ application calls DocTruth extraction
→ DocTruth returns auditable structured result
→ LangChain4j workflow uses the verified result downstream
```

Avoid passing unaudited model-generated fields into a system of record and then
asking DocTruth to reconstruct evidence later. Call DocTruth at the point where
the field is created.

## Spring AI Interop

DocTruth does not depend on Spring AI. This keeps the core library usable in
non-Spring and regulated environments.

Use Spring AI for application-level model interactions when useful. Use
DocTruth when the output must become auditable structured data.

Recommended split:

| Responsibility | Owner |
| --- | --- |
| General chat / assistants | Spring AI |
| Embeddings / vector store wiring | Spring AI or application |
| Evidence-backed document extraction | DocTruth |
| Citation and provenance output | DocTruth |

If an application already uses Spring AI model clients, keep that wiring at the
application boundary. DocTruth's built-in providers remain the evidence
extraction path until a dedicated provider adapter is added.

## Batch Jobs

For batch extraction, keep each document run independently auditable:

```text
for each document:
  parse
  extract
  write typed result
  write audit JSON
  record run id / source id / schema version
```

Do not merge audit JSON across documents unless a higher-level workflow creates
an explicit bundle. Single-run evidence should remain inspectable on its own.

## Storing Results

A practical storage layout:

| Artifact | Suggested Storage |
| --- | --- |
| Source file | Object storage or document store |
| Typed value | Application database |
| `ExtractionResult.toAuditJson()` | Object storage, audit table, or immutable log |
| Field-level review status | Application database |
| Rendered PDF overlay | Application-generated artifact |

The typed value and the audit artifact should share a durable run id in the
caller system.

## Error Handling

DocTruth uses checked exceptions at public boundaries:

| Exception | Meaning |
| --- | --- |
| `ParseException` | Source file could not be parsed |
| `ProviderException` | Model provider call failed |
| `ExtractionException` | Extraction, validation, retry, or citation requirement failed |

Recommended service behavior:

- Treat parse failures as document intake failures.
- Treat provider failures as retryable infrastructure failures when appropriate.
- Treat extraction validation failures as review or schema-quality failures.
- Store failed run metadata without storing secrets or full prompts in logs.

See [error handling](error-handling.md) for the detailed exception contract.

## Evidence UI

DocTruth is not a UI framework, but the evidence contract supports reviewer UI:

```text
field path
→ citation.exactQuote()
→ citation.location()
→ citation.boundingBox()
→ highlight source PDF region
```

For a lightweight visual example, see
[examples/evidence-overlay](../examples/evidence-overlay/).

## Deployment Shape

Short term, use DocTruth as an embedded library:

```text
Java service
└── doctruth-java dependency
```

For teams that want one shared extraction service across applications, the
natural next step is a private DocTruth sidecar/server:

```text
application services
→ DocTruth Server
→ model provider
→ typed result + audit JSON
```

That server shape is an operational product boundary. The OSS library remains
the single-document, single-run evidence primitive.
