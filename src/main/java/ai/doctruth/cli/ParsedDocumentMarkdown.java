package ai.doctruth.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import ai.doctruth.BlockKind;
import ai.doctruth.BoundingBox;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParsedSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

final class ParsedDocumentMarkdown {

    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?i)^(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|"
                    + "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|\\d{1,2}[/-]\\d{1,2})"
                    + "\\b.*\\b(?:to|-|present|current|now|\\d{4})\\b.*$");
    private static final Pattern BULLET_PREFIX =
            Pattern.compile("^\\s*(?:[-*+\\u2022]\\s+|\\d+[.)]\\s+|[a-zA-Z][.)]\\s+).+");

    private ParsedDocumentMarkdown() {
        throw new AssertionError("no instances");
    }

    static String toMarkdown(ParsedDocument doc) {
        var out = new StringBuilder();
        for (var block : coalesceContinuations(markdownOrder(doc.sections()))) {
            String rendered = renderBlock(block);
            appendBlock(out, rendered);
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static List<MarkdownBlock> markdownOrder(List<ParsedSection> sections) {
        var blocks = new ArrayList<MarkdownBlock>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            blocks.add(MarkdownBlock.from(sections.get(i), i));
        }
        blocks.sort(Comparator.comparingInt(
                        (MarkdownBlock block) -> block.location().pageStart())
                .thenComparingDouble(ParsedDocumentMarkdown::visualTop)
                .thenComparingDouble(ParsedDocumentMarkdown::visualLeft)
                .thenComparingInt(block -> block.location().lineStart())
                .thenComparingInt(MarkdownBlock::originalIndex));
        return blocks;
    }

    private static List<MarkdownBlock> coalesceContinuations(List<MarkdownBlock> blocks) {
        var out = new ArrayList<MarkdownBlock>(blocks.size());
        for (var block : blocks) {
            if (!out.isEmpty() && isContinuationOfPrevious(out.getLast(), block)) {
                out.set(out.size() - 1, out.getLast().append(block.text()));
            } else {
                out.add(block);
            }
        }
        return out;
    }

    private static boolean isContinuationOfPrevious(MarkdownBlock previous, MarkdownBlock current) {
        if (current.kind() != BlockKind.BODY || !startsLowercaseOrContinuation(current.text())) {
            return false;
        }
        if (!looksOpenEnded(previous.text())) {
            return false;
        }
        if (previous.boundingBox().isEmpty() || current.boundingBox().isEmpty()) {
            return true;
        }
        var prevBox = previous.boundingBox().orElseThrow();
        var curBox = current.boundingBox().orElseThrow();
        if (previous.location().pageEnd() != current.location().pageStart()) {
            return false;
        }
        double verticalGap = curBox.y0() - prevBox.y1();
        double indent = curBox.x0() - prevBox.x0();
        return verticalGap >= -3.0 && verticalGap <= 22.0 && indent >= 10.0;
    }

    private static boolean startsLowercaseOrContinuation(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return Character.isLowerCase(first) || Character.isDigit(first) || first == '(';
    }

    private static boolean looksOpenEnded(String text) {
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.endsWith("-") || trimmed.endsWith(",") || trimmed.endsWith("&")) {
            return true;
        }
        String lastLine =
                trimmed.lines().reduce((left, right) -> right).orElse(trimmed).stripTrailing();
        String lower = lastLine.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(" and") || lower.endsWith(" while") || lower.endsWith(" of") || lower.endsWith(" for");
    }

    private static double visualTop(MarkdownBlock block) {
        return block.boundingBox()
                .map(BoundingBox::y0)
                .orElse((double) block.location().lineStart() * 1000.0);
    }

    private static double visualLeft(MarkdownBlock block) {
        return block.boundingBox().map(BoundingBox::x0).orElse(0.0);
    }

    private static String renderBlock(MarkdownBlock block) {
        return switch (block.section()) {
            case TextSection ignored -> renderText(block);
            case TableSection table -> renderTable(table);
            case FigureSection figure -> renderFigure(figure);
        };
    }

    private static String renderText(TextSection section) {
        return renderText(MarkdownBlock.from(section, 0));
    }

    private static String renderText(MarkdownBlock section) {
        String text = normalizeText(section.text());
        if (text.isBlank()) {
            return "";
        }
        if (section.kind() == BlockKind.HEADING && shouldRenderHeading(text)) {
            return "## " + escapeInline(text);
        }
        if (section.kind() == BlockKind.LIST) {
            return renderListText(text);
        }
        return escapeInline(text);
    }

    private static boolean shouldRenderHeading(String text) {
        if (DATE_RANGE.matcher(text).matches()) {
            return false;
        }
        if (BULLET_PREFIX.matcher(text).matches()) {
            return false;
        }
        return text.length() <= 120;
    }

    private static String renderListText(String text) {
        String[] lines = text.split("\\R+");
        var out = new StringBuilder();
        for (String line : lines) {
            String normalized = normalizeText(line);
            if (normalized.isBlank()) {
                continue;
            }
            if (BULLET_PREFIX.matcher(normalized).matches()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(escapeInline(normalized));
            } else if (!out.isEmpty()) {
                if (out.charAt(out.length() - 1) == '-') {
                    out.append(escapeInline(normalized));
                } else {
                    out.append(' ').append(escapeInline(normalized));
                }
            } else {
                out.append("- ").append(escapeInline(normalized));
            }
        }
        return out.toString();
    }

    private static String renderTable(TableSection section) {
        List<List<String>> rows = section.rows();
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        var out = new StringBuilder();
        appendTableRow(out, rows.getFirst(), columns);
        out.append('\n');
        out.append('|');
        for (int i = 0; i < columns; i++) {
            out.append(" --- |");
        }
        for (int i = 1; i < rows.size(); i++) {
            out.append('\n');
            appendTableRow(out, rows.get(i), columns);
        }
        return out.toString();
    }

    private static void appendTableRow(StringBuilder out, List<String> row, int columns) {
        out.append('|');
        for (int i = 0; i < columns; i++) {
            String cell = i < row.size() ? row.get(i) : "";
            out.append(' ').append(escapeTableCell(normalizeText(cell))).append(" |");
        }
    }

    private static String renderFigure(FigureSection section) {
        String caption = normalizeText(section.caption());
        return caption.isBlank() ? "[Figure]" : "[Figure: " + escapeInline(caption) + "]";
    }

    private static void appendBlock(StringBuilder out, String rendered) {
        if (rendered.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        out.append(rendered);
    }

    private static String normalizeText(String text) {
        return text.replace('\u00a0', ' ').strip();
    }

    private static String escapeInline(String text) {
        return text.replace("\\", "\\\\").replace("_", "\\_").replace("`", "\\`");
    }

    private static String escapeTableCell(String text) {
        return escapeInline(text).replace("|", "\\|");
    }

    private record MarkdownBlock(
            ParsedSection section,
            String text,
            BlockKind kind,
            SourceLocation location,
            Optional<BoundingBox> boundingBox,
            int originalIndex) {

        static MarkdownBlock from(ParsedSection section, int originalIndex) {
            return switch (section) {
                case TextSection text ->
                    new MarkdownBlock(
                            section, text.text(), text.kind(), text.location(), text.boundingBox(), originalIndex);
                case TableSection table ->
                    new MarkdownBlock(section, "", BlockKind.OTHER, table.location(), Optional.empty(), originalIndex);
                case FigureSection figure ->
                    new MarkdownBlock(
                            section,
                            figure.caption(),
                            BlockKind.OTHER,
                            figure.location(),
                            figure.boundingBox(),
                            originalIndex);
            };
        }

        MarkdownBlock append(String continuation) {
            return new MarkdownBlock(section, text + "\n" + continuation, kind, location, boundingBox, originalIndex);
        }
    }
}
