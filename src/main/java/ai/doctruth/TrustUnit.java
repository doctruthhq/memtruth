package ai.doctruth;

import java.util.Objects;

/**
 * Smallest stable citeable atom inside a {@link TrustDocument}.
 *
 * @param unitId   stable unit id.
 * @param kind     unit kind.
 * @param location page and layout anchor.
 * @param content  text and source object identity.
 * @param evidence evidence links and warnings.
 * @since 1.0.0
 */
public record TrustUnit(
        String unitId, TrustUnitKind kind, TrustUnitLocation location, TrustUnitContent content, TrustUnitEvidence evidence) {

    public TrustUnit {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(evidence, "evidence");
        if (unitId.isBlank()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
    }
}

