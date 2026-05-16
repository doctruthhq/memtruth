# Spring Boot Integration

DocTruth is framework-agnostic, but it fits naturally into a Spring Boot
service. Keep DocTruth inside an application service boundary: controllers
should handle transport, services should call DocTruth, and repositories should
store the typed result plus audit JSON.

## Service Shape

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

## Provider Bean

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

## Storage Pattern

Store the structured value and audit artifact together:

| Artifact | Suggested Storage |
| --- | --- |
| Source file | Object storage |
| Extracted Java value | Application database |
| `result.toAuditJson()` | Audit table, object storage, or immutable log |
| Field review state | Application database |

The application should assign a durable run id that links the source document,
the extracted value, and the audit JSON.

## Error Handling

Recommended mapping:

| Exception | Application Meaning |
| --- | --- |
| `ParseException` | Document intake failed |
| `ProviderException` | Model provider or network failure |
| `ExtractionException` | Validation, retry, or citation requirement failed |

Do not log full prompts or source documents unless your deployment has explicit
approval to store that data.
