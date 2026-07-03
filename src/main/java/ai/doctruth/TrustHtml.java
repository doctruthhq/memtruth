package ai.doctruth;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;

/**
 * Small HTML passthrough helpers for document sources that are already HTML.
 *
 * <p>This is a conservative local converter for stable DocTruth contracts. A
 * fuller HTML dependency should be added behind an ADR when the renderer needs
 * broader HTML5 recovery.
 *
 * @since 1.0.0
 */
public final class TrustHtml {

    private static final Pattern PRE_CODE = Pattern.compile("(?is)<pre>\\s*<code[^>]*>(.*?)</code>\\s*</pre>");
    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[hd][^>]*>(.*?)</t[hd]>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

    private TrustHtml() {
        throw new AssertionError("no instances");
    }

    public static String toMarkdownPassthrough(String html) {
        Objects.requireNonNull(html, "html");
        String markdown = renderPreCode(html);
        markdown = renderTables(markdown);
        markdown = markdown.replaceAll("(?is)<h1[^>]*>(.*?)</h1>", "\n# $1\n");
        markdown = markdown.replaceAll("(?is)<h2[^>]*>(.*?)</h2>", "\n## $1\n");
        markdown = markdown.replaceAll("(?is)<h3[^>]*>(.*?)</h3>", "\n### $1\n");
        markdown = markdown.replaceAll("(?is)<strong[^>]*>(.*?)</strong>", "**$1**");
        markdown = markdown.replaceAll("(?is)<b[^>]*>(.*?)</b>", "**$1**");
        markdown = markdown.replaceAll("(?is)<em[^>]*>(.*?)</em>", "*$1*");
        markdown = markdown.replaceAll("(?is)<i[^>]*>(.*?)</i>", "*$1*");
        markdown = markdown.replaceAll("(?is)</p>", "\n\n");
        markdown = markdown.replaceAll("(?is)<br\\s*/?>", "\n");
        markdown = TAG.matcher(markdown).replaceAll("");
        return normalize(StringEscapeUtils.unescapeHtml4(markdown));
    }

    private static String renderPreCode(String html) {
        var matcher = PRE_CODE.matcher(html);
        var out = new StringBuilder();
        while (matcher.find()) {
            String code = StringEscapeUtils.unescapeHtml4(matcher.group(1)).strip();
            matcher.appendReplacement(out, "\n```\n" + escapeReplacement(code) + "\n```\n");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String renderTables(String html) {
        var matcher = TABLE.matcher(html);
        var out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, "\n" + escapeReplacement(tableMarkdown(matcher.group(1))) + "\n");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String tableMarkdown(String tableHtml) {
        var rows = new ArrayList<String>();
        var rowMatcher = ROW.matcher(tableHtml);
        while (rowMatcher.find()) {
            var cells = new ArrayList<String>();
            var cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(cleanInline(cellMatcher.group(1)));
            }
            if (!cells.isEmpty()) {
                rows.add(String.join(" | ", cells));
            }
        }
        return String.join("\n", rows);
    }

    private static String cleanInline(String html) {
        String withoutTags = TAG.matcher(html).replaceAll("");
        return StringEscapeUtils.unescapeHtml4(withoutTags)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String normalize(String markdown) {
        return markdown.replace("\r\n", "\n")
                        .replaceAll("[ \\t]+\\n", "\n")
                        .replaceAll("\\n{3,}", "\n\n")
                        .strip()
                + "\n";
    }

    private static String escapeReplacement(String value) {
        return value.replace("\\", "\\\\").replace("$", "\\$");
    }
}
