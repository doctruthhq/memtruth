package ai.doctruth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.pdfbox.text.TextPosition;

final class PdfLineSegment {

    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\s*\\d{1,2}.{0,2}[.)、]\\s+.*");
    private static final Pattern RESPONSIBILITY_SUBHEADING = Pattern.compile(
            ".*\\b(?:analysis|design|documentation|inspection|management|mapping|optimization|profiling|support|troubleshooting)\\b.*:",
            Pattern.CASE_INSENSITIVE);
    private static final double LINE_ASCENT_FACTOR = 1.67;
    private static final double LINE_DESCENT_FACTOR = 0.31;

    final List<TextPosition> positions;
    final String text;
    final double x0;
    final double x1;
    final double y0;
    final double y1;
    final double baseline;
    final boolean bold;
    int columnIndex = -1;

    private PdfLineSegment(
            List<TextPosition> positions,
            String text,
            double x0,
            double x1,
            double y0,
            double y1,
            double baseline,
            boolean bold) {
        this.positions = positions;
        this.text = text;
        this.x0 = x0;
        this.x1 = x1;
        this.y0 = y0;
        this.y1 = y1;
        this.baseline = baseline;
        this.bold = bold;
    }

    static PdfLineSegment from(List<TextPosition> positions) {
        var copy = PdfTextPositionMetrics.sortByX(positions).stream()
                .filter(p -> !PdfTextPositionMetrics.isBlank(p))
                .toList();
        double x0 = copy.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0.0);
        double x1 = copy.stream()
                .mapToDouble(p -> p.getXDirAdj() + p.getWidthDirAdj())
                .max()
                .orElse(x0);
        double baseline =
                copy.stream().mapToDouble(TextPosition::getYDirAdj).max().orElse(0.0);
        double height = copy.stream()
                .mapToDouble(TextPosition::getHeightDir)
                .max()
                .orElse(PdfTextPositionMetrics.MIN_LINE_HEIGHT);
        long boldCount = copy.stream().filter(PdfTextPositionMetrics::isBold).count();
        return new PdfLineSegment(
                new ArrayList<>(copy),
                PdfTextPositionMetrics.renderWithInferredSpaces(copy),
                x0,
                x1,
                baseline - height * LINE_ASCENT_FACTOR,
                baseline + height * LINE_DESCENT_FACTOR,
                baseline,
                boldCount > copy.size() / 2);
    }

    double width() {
        return Math.max(1.0, x1 - x0);
    }

    boolean isBoldResponsibilityHeading() {
        String stripped = text.strip();
        return stripped.length() >= 8
                && stripped.endsWith(":")
                && (bold || RESPONSIBILITY_SUBHEADING.matcher(stripped).matches());
    }

    boolean isResumeSectionHeading() {
        String stripped = text.strip();
        if (stripped.length() < 4 || stripped.length() > 48 || containsSentencePunctuation(stripped)) {
            return false;
        }
        if (isKnownResumeSection(stripped)) {
            return true;
        }
        return bold && uppercaseLetterRatio(stripped) >= 0.75;
    }

    boolean looksLikeInlineDate(Pattern dateRange) {
        return text.length() <= 40 && dateRange.matcher(text.strip()).matches();
    }

    boolean startsNumberedListItem() {
        return NUMBERED_ITEM.matcher(text).matches();
    }

    boolean looksLikeInlineFieldLabel() {
        String stripped = text.strip();
        return !isResumeSectionHeading()
                && stripped.length() >= 2
                && stripped.length() <= 32
                && (isKnownFieldLabel(stripped) || stripped.endsWith(":"))
                && uppercaseLetterRatio(stripped) < 0.75;
    }

    boolean looksLikeCompletedInlineField() {
        String stripped = text.strip();
        int colon = stripped.indexOf(':');
        return colon > 0 && colon < stripped.length() - 1 && !isKnownFieldLabel(stripped);
    }

    boolean looksLikeInlineFieldValue() {
        String stripped = text.strip();
        return !isResumeSectionHeading()
                && !isKnownFieldLabel(stripped)
                && stripped.length() >= 2
                && stripped.length() <= 24
                && !containsSentencePunctuation(stripped);
    }

    private static boolean isKnownResumeSection(String text) {
        return PdfResumeSectionNames.isKnown(text);
    }

    private static boolean isKnownFieldLabel(String text) {
        return switch (text.toLowerCase(Locale.ROOT).replace(":", "").strip()) {
            case "address",
                    "contact",
                    "contact number",
                    "current address",
                    "date of birth",
                    "email",
                    "email address",
                    "home address",
                    "linkedin",
                    "location",
                    "phone",
                    "phone number",
                    "tel",
                    "telephone" -> true;
            default -> false;
        };
    }

    private static boolean containsSentencePunctuation(String text) {
        return text.indexOf('.') >= 0 || text.indexOf(',') >= 0 || text.indexOf(';') >= 0;
    }

    private static double uppercaseLetterRatio(String text) {
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (Character.isUpperCase(c)) {
                uppercase++;
            }
        }
        return letters == 0 ? 0.0 : (double) uppercase / letters;
    }
}
