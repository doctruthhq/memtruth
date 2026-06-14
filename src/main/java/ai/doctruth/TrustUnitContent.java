package ai.doctruth;

import java.util.Objects;

/**
 * Text and source-object identity for a {@link TrustUnit}.
 *
 * @param text           unit text.
 * @param sourceObjectId parser source-object id backing this unit.
 * @since 1.0.0
 */
public record TrustUnitContent(String text, String sourceObjectId) {

    public TrustUnitContent {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceObjectId, "sourceObjectId");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (sourceObjectId.isBlank()) {
            throw new IllegalArgumentException("sourceObjectId must not be blank");
        }
    }
}

