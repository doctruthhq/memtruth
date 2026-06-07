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
    private static final double MAX_HORIZONTAL_SLOPE = 1.5;

    private PdfPageGraphicsExtractor() {
        throw new AssertionError("no instances");
    }

    static List<HorizontalSeparator> extractHorizontalSeparators(PDPage page) throws IOException {
        var engine = new SeparatorEngine(page);
        engine.processPage(page);
        return engine.separators();
    }

    record HorizontalSeparator(double x0, double x1, double y) {}

    private static final class SeparatorEngine extends PDFGraphicsStreamEngine {
        private final double pageHeight;
        private final List<HorizontalSeparator> separators = new ArrayList<>();
        private Point2D currentPoint;
        private Point2D pathStart;

        private SeparatorEngine(PDPage page) {
            super(page);
            pageHeight = page.getMediaBox().getHeight();
        }

        private List<HorizontalSeparator> separators() {
            return separators;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            addHorizontalEdge(p0, p1);
            addHorizontalEdge(p2, p3);
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
            separators.add(new HorizontalSeparator(x0, x1, yTopLeft));
        }
    }
}
