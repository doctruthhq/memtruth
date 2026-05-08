package ai.doctruth.spi;

/**
 * Callback the library invokes for every auditable event. Default
 * {@link #NOOP no-op} for callers who don't need observability; custom implementations
 * can forward events to systems such as Splunk, Datadog, CloudWatch, or internal audit
 * pipelines.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface AuditEventListener {
    void onEvent(AuditEvent event);

    /** No-op listener — drops every event. Default for callers who haven't opted in. */
    AuditEventListener NOOP = event -> {};
}
