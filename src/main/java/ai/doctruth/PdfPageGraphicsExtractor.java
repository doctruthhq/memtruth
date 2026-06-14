package ai.doctruth;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

final class PdfPageGraphicsExtractor {

    private static final double MIN_SEPARATOR_WIDTH = 72.0;
    private static final double MIN_SEPARATOR_HEIGHT = 24.0;
    private static final double MAX_HORIZONTAL_SLOPE = 1.5;
    private static final double MAX_VERTICAL_SLOPE = 1.5;

    private PdfPageGraphicsExtractor() {
        throw new AssertionError("no instances");
    }

    static List<HorizontalSeparator> extractHorizontalSeparators(PDPage page) throws IOException {
        var engine = new SeparatorEngine(page);
        engine.processPage(page);
        return engine.horizontalSeparators();
    }

    static GridLines extractGridLines(PDPage page) throws IOException {
        var engine = new SeparatorEngine(page);
        engine.processPage(page);
        return new GridLines(engine.horizontalSeparators(), engine.verticalSeparators());
    }

    record HorizontalSeparator(double x0, double x1, double y) {}

    record VerticalSeparator(double x, double y0, double y1) {}

    record GridLines(List<HorizontalSeparator> horizontal, List<VerticalSeparator> vertical) {}

    private static final class SeparatorEngine extends PDFGraphicsStreamEngine {
        private final double pageHeight;
        private final List<HorizontalSeparator> horizontalSeparators = new ArrayList<>();
        private final List<VerticalSeparator> verticalSeparators = new ArrayList<>();
        private Point2D currentPoint;
        private Point2D pathStart;

        private SeparatorEngine(PDPage page) {
            super(page);
            pageHeight = page.getMediaBox().getHeight();
        }

        private List<HorizontalSeparator> horizontalSeparators() {
            return List.copyOf(horizontalSeparators);
        }

        private List<VerticalSeparator> verticalSeparators() {
            return List.copyOf(verticalSeparators);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            addHorizontalEdge(p0, p1);
            addHorizontalEdge(p2, p3);
            addVerticalEdge(p1, p2);
            addVerticalEdge(p3, p0);
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // Image regions are useful for future OCR/layout heuristics, but not separators.
        }

        @Override
        public void clip(int windingRule) {
            // Clipping does not itself create a visible separator.
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Double(x, y);
            pathStart = currentPoint;
        }

        @Override
        public void lineTo(float x, float y) {
            var next = new Point2D.Double(x, y);
            addHorizontalEdge(currentPoint, next);
            addVerticalEdge(currentPoint, next);
            currentPoint = next;
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Double(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
            addHorizontalEdge(currentPoint, pathStart);
            addVerticalEdge(currentPoint, pathStart);
            currentPoint = pathStart;
        }

        @Override
        public void endPath() {
            currentPoint = null;
            pathStart = null;
        }

        @Override
        public void strokePath() {
            endPath();
        }

        @Override
        public void fillPath(int windingRule) {
            endPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            endPath();
        }

        @Override
        public void shadingFill(COSName shadingName) {
            // Shadings are ignored for separator extraction.
        }

        private void addHorizontalEdge(Point2D left, Point2D right) {
            if (left == null || right == null) {
                return;
            }
            double width = Math.abs(right.getX() - left.getX());
            double height = Math.abs(right.getY() - left.getY());
            if (width < MIN_SEPARATOR_WIDTH || height > MAX_HORIZONTAL_SLOPE) {
                return;
            }
            double x0 = Math.min(left.getX(), right.getX());
            double x1 = Math.max(left.getX(), right.getX());
            double yTopLeft = pageHeight - ((left.getY() + right.getY()) / 2.0);
            horizontalSeparators.add(new HorizontalSeparator(x0, x1, yTopLeft));
        }

        private void addVerticalEdge(Point2D top, Point2D bottom) {
            if (top == null || bottom == null) {
                return;
            }
            double width = Math.abs(bottom.getX() - top.getX());
            double height = Math.abs(bottom.getY() - top.getY());
            if (height < MIN_SEPARATOR_HEIGHT || width > MAX_VERTICAL_SLOPE) {
                return;
            }
            double x = (top.getX() + bottom.getX()) / 2.0;
            double y0 = pageHeight - Math.max(top.getY(), bottom.getY());
            double y1 = pageHeight - Math.min(top.getY(), bottom.getY());
            verticalSeparators.add(new VerticalSeparator(x, y0, y1));
        }
    }
}
