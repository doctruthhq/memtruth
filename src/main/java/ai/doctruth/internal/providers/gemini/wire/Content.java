package ai.doctruth.internal.providers.gemini.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Gemini {@code content} block: a role ({@code user} or {@code model}) plus an ordered
 * list of {@link Part}s.
 *
 * @hidden
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Content(String role, List<Part> parts) {}
