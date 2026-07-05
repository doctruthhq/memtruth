package ai.doctruth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DocTruth's canonical parser output for audit-ready extraction workflows.
 *
 * @param schemaVersion stable TrustDocument schema version.
 * @param docId         parser document id.
 * @param source        source file identity.
 * @param parserRun     parser provenance.
 * @param units         ordered audit-addressable content units.
 * @since 0.2.0
 */
public record TrustDocument(
        String schemaVersion,
        String docId,
        TrustDocumentSource source,
        ParserRun parserRun,
        List<TrustUnit> units) {

    public static final String SCHEMA_VERSION = "doctruth.trust-document.v1";

    public TrustDocument {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parserRun, "parserRun");
        Objects.requireNonNull(units, "units");
        if (schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        if (docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        units = List.copyOf(units);
    }

    public static TrustDocument fromParsed(ParsedDocument parsed, Path sourcePath, PdfParserBackend backend) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(backend, "backend");
        return fromParsed(parsed, sourcePath, backend.id());
    }

    public static TrustDocument fromParsed(ParsedDocument parsed, Path sourcePath, String backend) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(backend, "backend");
        var source = new TrustDocumentSource(
                sourceFilename(sourcePath), sha256FromDocId(parsed.docId()), parsed.metadata().pageCount());
        return new TrustDocument(SCHEMA_VERSION, parsed.docId(), source, new ParserRun(backend), units(parsed));
    }

    private static String sourceFilename(Path sourcePath) {
        Path filename = sourcePath.getFileName();
        if (filename == null || filename.toString().isBlank()) {
            throw new IllegalArgumentException("sourcePath must include a filename");
        }
        return filename.toString();
    }

    private static String sha256FromDocId(String docId) {
        if (docId.startsWith("sha256:")) {
            return docId.substring("sha256:".length());
        }
        throw new IllegalArgumentException("ParsedDocument docId must be sha256-backed");
    }

    private static List<TrustUnit> units(ParsedDocument parsed) {
        var units = new ArrayList<TrustUnit>(parsed.sections().size());
        for (int i = 0; i < parsed.sections().size(); i++) {
            units.add(unit("u" + (i + 1), parsed.sections().get(i)));
        }
        return units;
    }

    private static TrustUnit unit(String id, ParsedSection section) {
        return switch (section) {
            case TextSection text -> new TrustUnit(
                    id,
                    "text",
                    text.text(),
                    List.of(),
                    new TrustUnitEvidence(text.location(), text.boundingBox(), Optional.of(text.kind())));
            case TableSection table -> new TrustUnit(
                    id,
                    "table",
                    "",
                    table.rows(),
                    new TrustUnitEvidence(table.location(), Optional.empty(), Optional.empty()));
            case FigureSection figure -> new TrustUnit(
                    id,
                    "figure",
                    figure.caption(),
                    List.of(),
                    new TrustUnitEvidence(figure.location(), Optional.empty(), Optional.empty()));
        };
    }
}
