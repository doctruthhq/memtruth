package ai.doctruth.opendataloader;

import java.util.Comparator;
import java.util.List;

import ai.doctruth.TrustDocument;
import ai.doctruth.TrustTable;
import ai.doctruth.TrustTableCell;
import ai.doctruth.TrustUnit;
import ai.doctruth.TrustUnitKind;

/** Normalizes TrustDocument into OpenDataLoader-shaped projection objects. */
public final class OpenDataLoaderTrustDocumentAdapter {

    private OpenDataLoaderTrustDocumentAdapter() {
        throw new AssertionError("no instances");
    }

    public static List<OpenDataLoaderBlock> blocks(TrustDocument document) {
        return sortedUnits(document).stream()
                .map(OpenDataLoaderTrustDocumentAdapter::blockFrom)
                .toList();
    }

    public static List<OpenDataLoaderBlock> headings(TrustDocument document) {
        return blocks(document).stream()
                .filter(block -> "heading".equals(block.kind()))
                .toList();
    }

    public static List<OpenDataLoaderSourceRef> sourceMap(TrustDocument document) {
        return sortedUnits(document).stream()
                .map(OpenDataLoaderTrustDocumentAdapter::sourceRefFrom)
                .toList();
    }

    public static List<OpenDataLoaderTable> tables(TrustDocument document) {
        return document.body().tables().stream()
                .map(OpenDataLoaderTrustDocumentAdapter::tableFrom)
                .toList();
    }

    private static List<TrustUnit> sortedUnits(TrustDocument document) {
        return document.body().units().stream()
                .sorted(Comparator.comparingInt(unit -> unit.location().readingOrder()))
                .toList();
    }

    private static OpenDataLoaderBlock blockFrom(TrustUnit unit) {
        return new OpenDataLoaderBlock(
                "block-" + unit.unitId(),
                blockKind(unit),
                unit.location().page() - 1,
                unit.location().boundingBox(),
                unit.location().readingOrder(),
                unit.content().text(),
                unit.unitId());
    }

    private static String blockKind(TrustUnit unit) {
        if (unit.kind() == TrustUnitKind.HEADING) {
            return "heading";
        }
        if (unit.kind() == TrustUnitKind.TABLE_CELL) {
            return "table_cell";
        }
        if (unit.kind() == TrustUnitKind.FIGURE_CAPTION) {
            return "caption";
        }
        if (unit.kind() == TrustUnitKind.OCR_REGION) {
            return "ocr_region";
        }
        return "text";
    }

    private static OpenDataLoaderSourceRef sourceRefFrom(TrustUnit unit) {
        return new OpenDataLoaderSourceRef(
                unit.unitId(),
                unit.location().page() - 1,
                unit.location().boundingBox(),
                unit.content().text());
    }

    private static OpenDataLoaderTable tableFrom(TrustTable table) {
        return new OpenDataLoaderTable(
                table.tableId(),
                table.pageNumber() - 1,
                table.boundingBox(),
                table.cells().stream()
                        .map(OpenDataLoaderTrustDocumentAdapter::cellFrom)
                        .toList());
    }

    private static OpenDataLoaderTableCell cellFrom(TrustTableCell cell) {
        return new OpenDataLoaderTableCell(
                cell.cellId(),
                cell.rowRange().start(),
                cell.rowRange().end(),
                cell.columnRange().start(),
                cell.columnRange().end(),
                cell.boundingBox(),
                cell.text());
    }
}
