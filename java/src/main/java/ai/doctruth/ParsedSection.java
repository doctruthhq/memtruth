package ai.doctruth;

/**
 * A single section of a parsed source document. {@code ParsedSection} is sealed; the only
 * permitted implementations are {@link TextSection}, {@link TableSection}, and
 * {@link FigureSection}. New section types require a major version bump.
 *
 * <p>The sealed contract enables exhaustive {@code switch} pattern matching without a
 * {@code default} branch, so adding a new section type is a compile-time forcing function
 * everywhere it is consumed.
 *
 * @since 0.1.0
 */
public sealed interface ParsedSection permits TextSection, TableSection, FigureSection {}
