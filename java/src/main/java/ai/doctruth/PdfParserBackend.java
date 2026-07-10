package ai.doctruth;

import java.util.Locale;
import java.util.Objects;

/**
 * PDF parser backend exposed by the SDK and CLI.
 *
 * @since 0.2.0
 */
public enum PdfParserBackend {
    /** OpenDataLoader PDF backend; DocTruth's default PDF path. */
    OPENDATALOADER("opendataloader"),

    /** Legacy PDFBox layout parser, retained for compatibility and differential tests. */
    PDFBOX("pdfbox");

    private final String id;

    PdfParserBackend(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static PdfParserBackend fromId(String id) {
        Objects.requireNonNull(id, "id");
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (PdfParserBackend backend : values()) {
            if (backend.id.equals(normalized)) {
                return backend;
            }
        }
        throw new IllegalArgumentException("unsupported parser: " + id);
    }
}
