package ai.doctruth;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ai.doctruth.spi.SignatureProvider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical v1 document representation carrying parser provenance and trust evidence.
 *
 * @param docId            stable document identifier.
 * @param source           source metadata and hash.
 * @param body             pages, units, and tables.
 * @param parserRun        parser provenance.
 * @param auditGradeStatus audit eligibility state.
 * @since 1.0.0
 */
public record TrustDocument(
        String docId,
        TrustDocumentSource source,
        TrustDocumentBody body,
        ParserRun parserRun,
        AuditGradeStatus auditGradeStatus) {

    public TrustDocument {
        Objects.requireNonNull(docId, "docId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(parserRun, "parserRun");
        Objects.requireNonNull(auditGradeStatus, "auditGradeStatus");
        if (docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
    }

    /**
     * Converts the current Java parser contract into the v1 trust document contract.
     *
     * @param parsed     existing parsed document.
     * @param sourceHash stable source content hash.
     * @param parserRun  parser provenance.
     * @return evidence-native trust document.
     */
    public static TrustDocument fromParsed(ParsedDocument parsed, String sourceHash, ParserRun parserRun) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(parserRun, "parserRun");
        var source = new TrustDocumentSource(parsed.metadata().sourceFilename(), sourceHash, parsed.metadata());
        var body = bodyFrom(parsed, parserRun);
        return new TrustDocument(parsed.docId(), source, body, parserRun, AuditGradeStatus.UNKNOWN);
    }

    public static TrustDocument fromJsonFull(String json) {
        Objects.requireNonNull(json, "json");
        return TrustDocumentJson.fromJsonFull(json);
    }

    public String toJsonFull() {
        return TrustDocumentRenderers.toJsonFull(this);
    }

    public void writeJsonFull(Writer writer) throws IOException {
        TrustDocumentRenderers.writeJsonFull(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toJsonEvidence() {
        return TrustDocumentRenderers.toJsonEvidence(this);
    }

    public void writeJsonEvidence(Writer writer) throws IOException {
        TrustDocumentRenderers.writeJsonEvidence(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toMarkdownClean() {
        return TrustDocumentRenderers.toMarkdownClean(this);
    }

    public String toMarkdownAnchored() {
        return TrustDocumentRenderers.toMarkdownAnchored(this);
    }

    public void writeMarkdownAnchored(Writer writer) throws IOException {
        TrustDocumentRenderers.writeMarkdownAnchored(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toMarkdownReview() {
        return TrustDocumentRenderers.toMarkdownReview(this);
    }

    public void writeMarkdownReview(Writer writer) throws IOException {
        TrustDocumentRenderers.writeMarkdownReview(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toPlainText() {
        return TrustDocumentRenderers.toPlainText(this);
    }

    public void writePlainText(Writer writer) throws IOException {
        TrustDocumentRenderers.writePlainText(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toCompactLlm() {
        return TrustDocumentRenderers.toCompactLlm(this);
    }

    public void writeCompactLlm(Writer writer) throws IOException {
        TrustDocumentRenderers.writeCompactLlm(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toJsonLines() {
        return TrustDocumentRenderers.toJsonLines(this);
    }

    public void writeJsonLines(Writer writer) throws IOException {
        TrustDocumentRenderers.writeJsonLines(this, Objects.requireNonNull(writer, "writer"));
    }

    public void writeContentBlocks(Writer writer) throws IOException {
        TrustDocumentRenderers.writeContentBlocks(this, Objects.requireNonNull(writer, "writer"));
    }

    public void writeParseTrace(Writer writer) throws IOException {
        TrustDocumentRenderers.writeParseTrace(this, Objects.requireNonNull(writer, "writer"));
    }

    public TrustDocument withLayeredOutputs(JsonNode contentBlocks, JsonNode parseTrace) {
        TrustDocumentLayeredOutputs.attach(this, contentBlocks, parseTrace);
        return this;
    }

    public String toAuditJson() {
        return TrustDocumentRenderers.toAuditJson(this);
    }

    public void writeAuditJson(Writer writer) throws IOException {
        TrustDocumentRenderers.writeAuditJson(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toAuditJson(SignatureProvider signer) {
        Objects.requireNonNull(signer, "signer");
        return signer.sign(toAuditJson());
    }

    public void toAuditJson(Path path, SignatureProvider signer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(signer, "signer");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toAuditJson(signer));
    }

    public void writeMarkdownClean(Writer writer) throws IOException {
        TrustDocumentRenderers.writeMarkdownClean(this, Objects.requireNonNull(writer, "writer"));
    }

    public String canonicalHash() {
        return TrustDocumentRenderers.canonicalHash(this);
    }

    public TrustRenderedDocument toMarkdownWithSourceMap() {
        return TrustDocumentRenderers.toMarkdownWithSourceMap(this);
    }

    public TrustRenderedDocument toCompactLlmWithSourceMap() {
        return TrustDocumentRenderers.toCompactLlmWithSourceMap(this);
    }

    public void writeMarkdownSourceMap(Writer writer) throws IOException {
        TrustDocumentRenderers.writeMarkdownSourceMap(this, Objects.requireNonNull(writer, "writer"));
    }

    public void writeCompactLlmSourceMap(Writer writer) throws IOException {
        TrustDocumentRenderers.writeCompactLlmSourceMap(this, Objects.requireNonNull(writer, "writer"));
    }

    public String toHtmlReview() {
        return TrustDocumentRenderers.toHtmlReview(this);
    }

    public void writeHtmlReview(Writer writer) throws IOException {
        TrustDocumentRenderers.writeHtmlReview(this, Objects.requireNonNull(writer, "writer"));
    }

    public List<TrustDocumentChunk> toChunks(int maxChars) {
        if (maxChars < 16) {
            throw new IllegalArgumentException("maxChars must be >= 16");
        }
        return TrustDocumentRenderers.toChunks(this, maxChars);
    }

    public TrustDocument withEvaluatedAuditGrade() {
        var status = isAuditGradeEligible() ? AuditGradeStatus.AUDIT_GRADE : AuditGradeStatus.NOT_AUDIT_GRADE;
        return new TrustDocument(docId, source, body, parserRun, status);
    }

    private boolean isAuditGradeEligible() {
        return !body.units().isEmpty()
                && parserRun.warnings().stream().noneMatch(TrustDocument::isSevere)
                && body.units().stream().allMatch(TrustDocument::unitIsAuditGradeEligible);
    }

    private static boolean unitIsAuditGradeEligible(TrustUnit unit) {
        return !unit.evidence().evidenceSpanIds().isEmpty()
                && unit.evidence().warnings().stream().noneMatch(TrustDocument::isSevere);
    }

    private static boolean isSevere(ParserWarning warning) {
        return warning.severity() == ParserWarningSeverity.SEVERE;
    }

    private static TrustDocumentBody bodyFrom(ParsedDocument parsed, ParserRun parserRun) {
        var units = new ArrayList<TrustUnit>();
        var tables = new ArrayList<TrustTable>();
        int unitIndex = 1;
        int tableIndex = 1;
        for (var section : parsed.sections()) {
            switch (section) {
                case TextSection text -> addTextUnit(units, unitIndex++, text, parserRun);
                case FigureSection figure -> addFigureUnit(units, unitIndex++, figure);
                case TableSection table -> {
                    var adapted = tableFrom(table, tableIndex++, unitIndex);
                    tables.add(adapted.table());
                    units.addAll(adapted.units());
                    unitIndex += adapted.units().size();
                }
            }
        }
        return new TrustDocumentBody(pagesFrom(parsed.metadata()), units, tables);
    }

    private static List<TrustPage> pagesFrom(DocumentMetadata metadata) {
        var pages = new ArrayList<TrustPage>(metadata.pageCount());
        for (int i = 1; i <= metadata.pageCount(); i++) {
            pages.add(new TrustPage(i, 1000, 1000, true, ""));
        }
        return pages;
    }

    private static void addTextUnit(List<TrustUnit> units, int unitIndex, TextSection section, ParserRun parserRun) {
        if (section.text().isBlank()) {
            return;
        }
        units.add(new TrustUnit(
                unitId(unitIndex),
                trustUnitKind(section, parserRun),
                locationFrom(section.location(), section.boundingBox(), unitIndex),
                new TrustUnitContent(section.text(), sourceObjectId(unitIndex)),
                evidenceFrom(unitIndex)));
    }

    private static TrustUnitKind trustUnitKind(TextSection section, ParserRun parserRun) {
        if (section.kind() == BlockKind.HEADING) {
            return TrustUnitKind.HEADING;
        }
        return parserRun.backend().contains("ocr") ? TrustUnitKind.OCR_REGION : TrustUnitKind.TEXT_BLOCK;
    }

    private static void addFigureUnit(List<TrustUnit> units, int unitIndex, FigureSection section) {
        String caption = section.caption().isBlank() ? "[Figure]" : section.caption();
        units.add(new TrustUnit(
                unitId(unitIndex),
                TrustUnitKind.FIGURE_CAPTION,
                locationFrom(section.location(), Optional.empty(), unitIndex),
                new TrustUnitContent(caption, sourceObjectId(unitIndex)),
                evidenceFrom(unitIndex)));
    }

    private static AdaptedTable tableFrom(TableSection section, int tableIndex, int firstUnitIndex) {
        var cells = new ArrayList<TrustTableCell>();
        var units = new ArrayList<TrustUnit>();
        int unitIndex = firstUnitIndex;
        if (section.cellRegions().isEmpty()) {
            unitIndex = addUnboundedTableCells(section, tableIndex, cells, units, unitIndex);
        } else {
            unitIndex = addRegionBackedTableCells(section, tableIndex, cells, units, unitIndex);
        }
        var table = new TrustTable(
                "table-%04d".formatted(tableIndex),
                section.location().pageStart(),
                section.boundingBox(),
                new Confidence(1.0, "java parser table section"),
                cells);
        return new AdaptedTable(table, units);
    }

    private static int addUnboundedTableCells(
            TableSection section, int tableIndex, List<TrustTableCell> cells, List<TrustUnit> units, int unitIndex) {
        int columnCount = tableColumnCount(section);
        for (int row = 0; row < section.rows().size(); row++) {
            for (int column = 0; column < columnCount; column++) {
                String text = tableCellText(section, row, column);
                String cellId = "cell-%04d-%04d-%04d".formatted(tableIndex, row, column);
                cells.add(new TrustTableCell(
                        cellId,
                        new TrustCellRange(row, row),
                        new TrustCellRange(column, column),
                        Optional.empty(),
                        text));
                if (!text.isBlank()) {
                    units.add(tableCellUnit(unitIndex++, section.location(), Optional.empty(), text, cellId));
                }
            }
        }
        return unitIndex;
    }

    private static int addRegionBackedTableCells(
            TableSection section, int tableIndex, List<TrustTableCell> cells, List<TrustUnit> units, int unitIndex) {
        int columnCount = tableColumnCount(section);
        for (int row = 0; row < section.rows().size(); row++) {
            for (int column = 0; column < columnCount; column++) {
                if (coveredBySpanningRegion(section, row, column)) {
                    continue;
                }
                var region = tableCellRegion(section, row, column);
                String text = tableCellText(section, row, column);
                String cellId = "cell-%04d-%04d-%04d".formatted(tableIndex, row, column);
                var cellBox = region.map(TableCellRegion::boundingBox);
                var cellLocation = region
                        .map(value -> new SourceLocation(
                                value.page(),
                                value.page(),
                                section.location().lineStart(),
                                section.location().lineEnd(),
                                section.location().charOffset()))
                        .orElse(section.location());
                cells.add(new TrustTableCell(
                        cellId,
                        new TrustCellRange(row, region.map(TableCellRegion::rowEnd).orElse(row)),
                        new TrustCellRange(column, region.map(TableCellRegion::columnEnd).orElse(column)),
                        cellBox,
                        text));
                if (!text.isBlank()) {
                    units.add(tableCellUnit(unitIndex++, cellLocation, cellBox, text, cellId));
                }
            }
        }
        return unitIndex;
    }

    private static boolean coveredBySpanningRegion(TableSection section, int row, int column) {
        return section.cellRegions().stream()
                .filter(region -> region.row() != row || region.column() != column)
                .anyMatch(region -> region.row() <= row
                        && region.rowEnd() >= row
                        && region.column() <= column
                        && region.columnEnd() >= column);
    }

    private static Optional<TableCellRegion> tableCellRegion(TableSection section, int row, int column) {
        return section.cellRegions().stream()
                .filter(region -> region.row() == row)
                .filter(region -> region.column() == column)
                .findFirst();
    }

    private static String tableCellText(TableSection section, int row, int column) {
        if (row >= section.rows().size() || column >= section.rows().get(row).size()) {
            return "";
        }
        return section.rows().get(row).get(column);
    }

    private static int tableColumnCount(TableSection section) {
        return section.rows().stream().mapToInt(List::size).max().orElse(0);
    }

    private static TrustUnit tableCellUnit(
            int unitIndex, SourceLocation location, Optional<BoundingBox> boundingBox, String text, String cellId) {
        return new TrustUnit(
                unitId(unitIndex),
                TrustUnitKind.TABLE_CELL,
                locationFrom(location, boundingBox, unitIndex),
                new TrustUnitContent(text, cellId),
                evidenceFrom(unitIndex));
    }

    private static TrustUnitLocation locationFrom(
            SourceLocation location, Optional<BoundingBox> boundingBox, int readingOrder) {
        return new TrustUnitLocation(location.pageStart(), boundingBox, readingOrder);
    }

    private static TrustUnitEvidence evidenceFrom(int index) {
        return new TrustUnitEvidence(List.of("span-%04d".formatted(index)), new Confidence(1.0, "parsed source"), List.of());
    }

    private static String unitId(int index) {
        return "unit-%04d".formatted(index);
    }

    private static String sourceObjectId(int index) {
        return "section-%04d".formatted(index);
    }

    private record AdaptedTable(TrustTable table, List<TrustUnit> units) {}
}
