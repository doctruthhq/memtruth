package ai.doctruth;

/**
 * Citeable unit kind in a {@link TrustDocument}.
 *
 * @since 1.0.0
 */
public enum TrustUnitKind {
    TEXT_BLOCK,
    LINE_SPAN,
    TABLE_CELL,
    FIGURE_CAPTION,
    KEY_VALUE_REGION,
    OCR_REGION,
    HEADING
}
