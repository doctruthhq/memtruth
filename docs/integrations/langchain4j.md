# LangChain4j Integration

DocTruth is not a LangChain4j replacement. Use LangChain4j for orchestration,
tools, retrieval, memory, and agent-style workflows. Use DocTruth when a
document field must become auditable structured data.

## Recommended Split

| Responsibility | Owner |
| --- | --- |
| Workflow orchestration | LangChain4j |
| Tools and retrieval | LangChain4j or application code |
| Document parsing to source-located sections | DocTruth |
| Schema-bound extraction | DocTruth |
| Field citation matching | DocTruth |
| PROV-O audit JSON | DocTruth |

## Flow

```text
LangChain4j workflow chooses document and schema
→ application calls DocTruth
→ DocTruth returns typed value + citations + confidence + provenance
→ workflow uses verified result downstream
```

The key is to call DocTruth where the field is created. Avoid generating
unaudited model fields first and asking DocTruth to reconstruct evidence later.

## Example Boundary

```java
public final class VerifiedExtractionTool {

    private final LlmProvider provider;

    public VerifiedExtractionTool(LlmProvider provider) {
        this.provider = provider;
    }

    public ExtractionResult<Contract> extractContract(Path pdf)
            throws ParseException, ExtractionException {
        return DocTruth.withProvider(provider)
                .fromPdf(pdf)
                .extract("Extract contract terms", Contract.class)
                .withEvidence()
                .run();
    }
}
```

LangChain4j can call this boundary as a normal application tool, but the
evidence contract stays owned by DocTruth.

## Why This Matters

General agent frameworks are good at deciding what to do next. They usually do
not enforce that every structured field can cite a source page, source quote,
match score, model version, and extraction timestamp. DocTruth provides that
evidence-gated extraction boundary for Java applications.
