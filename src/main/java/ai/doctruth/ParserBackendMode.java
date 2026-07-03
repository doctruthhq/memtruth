package ai.doctruth;

/**
 * SDK parser backend selection.
 *
 * <p>{@link #AUTO} is the production default: require the configured Rust
 * runtime. {@link #PDFBOX} is an explicit legacy/oracle mode for local
 * debugging, migration, and regression comparison only.
 *
 * @since 1.0.0
 */
public enum ParserBackendMode {
    AUTO,
    PDFBOX,
    SIDECAR
}
