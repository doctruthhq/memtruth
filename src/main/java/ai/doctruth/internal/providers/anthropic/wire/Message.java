package ai.doctruth.internal.providers.anthropic.wire;

/**
 * One element of {@link MessagesRequest#messages()}. v0.1.0-alpha sends only string content
 * with role {@code "user"}; tool-use forcing arrives in Phase 2.
 *
 * @hidden
 */
public record Message(String role, String content) {}
