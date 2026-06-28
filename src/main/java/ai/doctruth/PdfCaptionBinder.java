package ai.doctruth;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class PdfCaptionBinder {

    private static final double MAX_CAPTION_TABLE_GAP = 80.0;
    private static final Pattern CAPTION_PREFIX =
            Pattern.compile("^(?i)(?:table|fig\\.?|figure)\\s+\\d+(?:[.-]\\d+)*\\s*[.:\\-]?\\s+\\S.*$");

    private PdfCaptionBinder() {
        throw new AssertionError("no instances");
    }

    static Optional<FigureSection> bindCaption(PdfTextBlock block, List<PdfPageTableExtractor.TableBlock> tables) {
        if (!isStandaloneCaption(block) || block.boundingBox().isEmpty()) {
            return Optional.empty();
        }
        var captionBox = block.boundingBox().get();
        boolean adjacentToTable = tables.stream()
                .map(PdfPageTableExtractor.TableBlock::boundingBox)
                .anyMatch(tableBox -> horizontallyOverlaps(captionBox, tableBox)
                        && verticalGap(captionBox, tableBox) <= MAX_CAPTION_TABLE_GAP);
        if (!adjacentToTable) {
            return Optional.empty();
        }
        return Optional.of(new FigureSection(block.text(), block.location(), block.boundingBox()));
    }

    private static boolean isStandaloneCaption(PdfTextBlock block) {
        String text = block.text().strip();
        return !text.contains("\n") && CAPTION_PREFIX.matcher(text).matches();
    }

    private static boolean horizontallyOverlaps(BoundingBox caption, BoundingBox table) {
        return Math.max(caption.x0(), table.x0()) < Math.min(caption.x1(), table.x1());
    }

    private static double verticalGap(BoundingBox caption, BoundingBox table) {
        if (caption.y1() <= table.y0()) {
            return table.y0() - caption.y1();
        }
        if (table.y1() <= caption.y0()) {
            return caption.y0() - table.y1();
        }
        return 0.0;
    }
}
