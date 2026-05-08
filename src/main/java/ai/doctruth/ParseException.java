package ai.doctruth;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Thrown by Layer 1 document parsers (PDF / DOCX) when a source file cannot be parsed or
 * when a structural invariant is violated. Carries the source filename and (when known)
 * the page number where parsing failed — enough detail for a downstream auditor to open the
 * exact page in a reader.
 *
 * <p>Checked.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code errorCode} non-null and non-blank.
 *   <li>{@code message} non-null.
 *   <li>{@code sourceName} non-null and non-blank.
 *   <li>{@code pageNumber} is a non-null {@link OptionalInt}; pass {@link OptionalInt#empty()}
 *       (not {@code null}) when the failure cannot be tied to a specific page.
 * </ul>
 *
 * @since 0.1.0
 */
public class ParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String sourceName;
    // OptionalInt is intentionally non-Serializable (per its Javadoc); we store the value +
    // presence flag so the enclosing Throwable's Serializable contract holds without leaning
    // on @SuppressWarnings("serial"). The accessor repackages into OptionalInt.
    private final int pageNumberValue;
    private final boolean pageNumberPresent;

    public ParseException(String errorCode, String message, String sourceName, OptionalInt pageNumber) {
        this(errorCode, message, sourceName, pageNumber, null);
    }

    public ParseException(
            String errorCode, String message, String sourceName, OptionalInt pageNumber, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(pageNumber, "pageNumber");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }
        this.errorCode = errorCode;
        this.sourceName = sourceName;
        this.pageNumberPresent = pageNumber.isPresent();
        this.pageNumberValue = this.pageNumberPresent ? pageNumber.getAsInt() : 0;
    }

    public String errorCode() {
        return errorCode;
    }

    public String sourceName() {
        return sourceName;
    }

    public OptionalInt pageNumber() {
        return pageNumberPresent ? OptionalInt.of(pageNumberValue) : OptionalInt.empty();
    }
}
