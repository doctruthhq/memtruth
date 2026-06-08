package ai.doctruth;

import java.util.List;
import java.util.Optional;

final class PdfTextBlockGeometry {

    private PdfTextBlockGeometry() {
        throw new AssertionError("no instances");
    }

    static PdfTextBlock merge(List<PdfTextBlock> blocks) {
        if (blocks.size() == 1) {
            return blocks.getFirst();
        }
        blocks.sort(PdfTextBlockGeometry::compareTopLeft);
        var first = blocks.getFirst();
        var last = blocks.getLast();
        var text = new StringBuilder();
        for (var block : blocks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(block.text());
        }
        return new PdfTextBlock(
                text.toString(),
                first.kind(),
                new SourceLocation(
                        first.location().pageStart(),
                        last.location().pageEnd(),
                        first.location().lineStart(),
                        Math.max(first.location().lineEnd(), last.location().lineEnd()),
                        first.location().charOffset()),
                unionBox(blocks));
    }

    static int compareTopLeft(PdfTextBlock left, PdfTextBlock right) {
        int y = Double.compare(top(left), top(right));
        return y != 0 ? y : Double.compare(left(left), left(right));
    }

    static double top(PdfTextBlock block) {
        return block.boundingBox().map(BoundingBox::y0).orElse(Double.POSITIVE_INFINITY);
    }

    static double left(PdfTextBlock block) {
        return block.boundingBox().map(BoundingBox::x0).orElse(Double.POSITIVE_INFINITY);
    }

    static double centerX(BoundingBox box) {
        return (box.x0() + box.x1()) / 2.0;
    }

    static boolean sameRow(PdfTextBlock left, PdfTextBlock right) {
        if (left.boundingBox().isEmpty() || right.boundingBox().isEmpty()) {
            return false;
        }
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        double overlap = Math.max(0.0, Math.min(a.y1(), b.y1()) - Math.max(a.y0(), b.y0()));
        double minHeight = Math.max(1.0, Math.min(a.y1() - a.y0(), b.y1() - b.y0()));
        return overlap / minHeight >= 0.45 || Math.abs(centerY(a) - centerY(b)) <= 6.0;
    }

    static double horizontalGap(PdfTextBlock left, PdfTextBlock right) {
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        if (a.x1() <= b.x0()) {
            return b.x0() - a.x1();
        }
        if (b.x1() <= a.x0()) {
            return a.x0() - b.x1();
        }
        return 0.0;
    }

    static boolean isToRightOf(PdfTextBlock left, PdfTextBlock right) {
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        return b.x0() >= a.x1() - 2.0;
    }

    private static Optional<BoundingBox> unionBox(List<PdfTextBlock> blocks) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (var block : blocks) {
            if (block.boundingBox().isEmpty()) {
                continue;
            }
            var box = block.boundingBox().orElseThrow();
            x0 = Math.min(x0, box.x0());
            y0 = Math.min(y0, box.y0());
            x1 = Math.max(x1, box.x1());
            y1 = Math.max(y1, box.y1());
            found = true;
        }
        return found ? Optional.of(new BoundingBox(x0, y0, x1, y1)) : Optional.empty();
    }

    private static double centerY(BoundingBox box) {
        return (box.y0() + box.y1()) / 2.0;
    }
}
