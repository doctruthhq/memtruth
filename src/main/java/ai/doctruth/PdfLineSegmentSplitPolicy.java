package ai.doctruth;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfLineSegmentSplitPolicy {

    private static final double LATERAL_JUMP_FACTOR = 10.0;
    private static final double LATERAL_JUMP_MIN = 120.0;
    private static final double CONTACT_DATUM_SPLIT_GAP = 24.0;
    private static final Pattern DATE_RANGE = Pattern.compile(
            ".*\\b(?:19|20)\\d{2}\\b\\s*(?:[-–—]|to)\\s*(?:\\b(?:19|20)\\d{2}\\b|present|now).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT_DATUM = Pattern.compile(
            "^(?:\\+?\\d[\\d\\s().-]{6,}|[\\w.+-]+@[\\w.-]+|Email:.+|Tell\\s*:.+|Tel\\s*:.+).*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CJK_TEXT = Pattern.compile(".*\\p{IsHan}.*");
    private static final Pattern SIDEBAR_BOUNDARY_TEXT = Pattern.compile(
            "^(?:address|contact|email|linkedin|objective|phone|quality|references|skills|tel|telephone)$",
            Pattern.CASE_INSENSITIVE);

    private PdfLineSegmentSplitPolicy() {
        throw new AssertionError("no instances");
    }

    static boolean shouldSplitLineSegment(
            List<TextPosition> current, TextPosition previous, TextPosition next, double splitGap) {
        double gap = PdfTextPositionMetrics.horizontalGap(previous, next);
        if (gap > splitGap) {
            return true;
        }
        String currentText = PdfTextPositionMetrics.renderWithInferredSpaces(current).strip();
        return gap > CONTACT_DATUM_SPLIT_GAP && CONTACT_DATUM.matcher(currentText).matches();
    }

    static boolean isUnrelatedLateralJump(
            List<TextPosition> current, PdfLineSegment lastLine, PdfLineSegment line, float lineHeight) {
        if (line.looksLikeInlineDate(DATE_RANGE)
                || line.looksLikeInlineFieldValue()
                || containsCjkText(lastLine.text)
                || containsCjkText(line.text)
                || isReturningToGroupLeftEdge(current, lastLine, line, lineHeight)) {
            return false;
        }
        double overlap = Math.max(0.0, Math.min(lastLine.x1, line.x1) - Math.max(lastLine.x0, line.x0));
        if (overlap > 0.0) {
            return false;
        }
        if (!hasSidebarBoundaryContext(current, lastLine, line) && looksLikeTableCellPair(lastLine, line)) {
            return false;
        }
        double xJump = Math.abs(line.x0 - lastLine.x0);
        return xJump > Math.max(LATERAL_JUMP_MIN, lineHeight * LATERAL_JUMP_FACTOR);
    }

    static boolean isInlineDate(PdfLineSegment line) {
        return line.looksLikeInlineDate(DATE_RANGE);
    }

    private static boolean containsCjkText(String text) {
        return CJK_TEXT.matcher(text).matches();
    }

    private static boolean isReturningToGroupLeftEdge(
            List<TextPosition> current, PdfLineSegment lastLine, PdfLineSegment line, float lineHeight) {
        if (!lastLine.looksLikeInlineDate(DATE_RANGE) && !lastLine.looksLikeInlineFieldValue()) {
            return false;
        }
        double groupLeft = current.stream()
                .filter(p -> !PdfTextPositionMetrics.isBlank(p))
                .mapToDouble(TextPosition::getXDirAdj)
                .min()
                .orElse(lastLine.x0);
        return Math.abs(line.x0 - groupLeft) <= Math.max(72.0, lineHeight * 6.0);
    }

    private static boolean hasSidebarBoundaryContext(
            List<TextPosition> current, PdfLineSegment lastLine, PdfLineSegment line) {
        return isSidebarBoundary(lastLine.text)
                || isSidebarBoundary(line.text)
                || PdfVisualTextLayout.renderGroup(current).lines().anyMatch(PdfLineSegmentSplitPolicy::isSidebarBoundary);
    }

    private static boolean isSidebarBoundary(String text) {
        return SIDEBAR_BOUNDARY_TEXT.matcher(text.strip().replace(":", "")).matches();
    }

    private static boolean looksLikeTableCellPair(PdfLineSegment left, PdfLineSegment right) {
        return looksLikeDenseTableCell(left.text) && looksLikeDenseTableCell(right.text);
    }

    private static boolean looksLikeDenseTableCell(String text) {
        String stripped = text.strip();
        if (stripped.length() > 48) {
            return false;
        }
        if (DATE_RANGE.matcher(stripped).matches()) {
            return true;
        }
        int signal = 0;
        int lettersOrDigits = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            lettersOrDigits++;
            if (Character.isDigit(c) || Character.isUpperCase(c)) {
                signal++;
            }
        }
        return lettersOrDigits > 0 && (double) signal / lettersOrDigits >= 0.70;
    }
}
