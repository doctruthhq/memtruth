package ai.doctruth;

import java.util.Objects;

/**
 * Public entry point for the library.
 *
 * <pre>{@code
 * var result = DocTruth.from(provider)
 *     .extract("Extract the contract terms", Contract.class)
 *     .withProvenance()
 *     .run(parsedDoc);
 * }</pre>
 *
 * @since 0.1.0
 */
public final class DocTruth {

    private final LlmProvider provider;

    private DocTruth(LlmProvider provider) {
        this.provider = provider;
    }

    /**
     * Begin an extraction pipeline against the given provider.
     *
     * @throws NullPointerException if {@code provider} is null.
     */
    public static DocTruth from(LlmProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new DocTruth(provider);
    }

    /**
     * Begin the document-first happy path with an explicit provider.
     *
     * <pre>{@code
     * var result = DocTruth.withProvider(provider)
     *     .fromPdf(Path.of("resume.pdf"))
     *     .extract("Extract resume fields", Resume.class)
     *     .withEvidence()
     *     .run();
     * }</pre>
     *
     * @throws NullPointerException if {@code provider} is null.
     */
    public static DocTruthClient withProvider(LlmProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new DocTruthClient(provider);
    }

    /**
     * Begin the document-first happy path with the OpenAI provider using
     * {@code OPENAI_API_KEY} from the process environment.
     *
     * @throws IllegalStateException if {@code OPENAI_API_KEY} is absent or blank.
     */
    public static DocTruthClient withOpenAi() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        return withOpenAi(apiKey);
    }

    /**
     * Begin the document-first happy path with the OpenAI provider and an explicit key.
     *
     * @throws NullPointerException     if {@code apiKey} is null.
     * @throws IllegalArgumentException if {@code apiKey} is blank.
     */
    public static DocTruthClient withOpenAi(String apiKey) {
        return withProvider(new OpenAiProvider(apiKey));
    }

    /**
     * Stage an extraction call: pair a free-text prompt with the target type.
     *
     * @throws NullPointerException     if {@code prompt} or {@code type} is null.
     * @throws IllegalArgumentException if {@code prompt} is blank.
     */
    public <T> ExtractionBuilder<T> extract(String prompt, Class<T> type) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(type, "type");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return ExtractionBuilder.create(provider, prompt, type);
    }

    /**
     * Stage a JSON Schema-driven extraction call. Use this overload for schemas exported
     * by another runtime such as Pydantic; the result is returned as Jackson {@code JsonNode}.
     *
     * @throws NullPointerException     if {@code prompt} or {@code schema} is null.
     * @throws IllegalArgumentException if {@code prompt} is blank.
     */
    public JsonExtractionBuilder extractJson(String prompt, JsonSchema schema) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(schema, "schema");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return JsonExtractionBuilder.create(provider, prompt, schema);
    }
}
