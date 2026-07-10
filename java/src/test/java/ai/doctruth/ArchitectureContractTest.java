package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureContractTest {

    @Test
    @DisplayName("main source files stay within the canonical line-count limit")
    void mainSourceFileLineCount() throws IOException {
        assertFilesUnderLineLimit(Path.of("src/main/java"), 300);
    }

    @Test
    @DisplayName("test source files stay within the canonical line-count limit")
    void testSourceFileLineCount() throws IOException {
        assertFilesUnderLineLimit(Path.of("src/test/java"), 500);
    }

    @Test
    @DisplayName("public records stay within the canonical component-count limit")
    void publicRecordComponentCount() throws IOException {
        assertThat(publicRecordViolations()).isEmpty();
    }

    private static void assertFilesUnderLineLimit(Path root, int maxLines) throws IOException {
        assertThat(lineLimitViolations(root, maxLines)).isEmpty();
    }

    private static List<String> lineLimitViolations(Path root, int maxLines) throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> addLineViolation(violations, p, maxLines));
        }
        return violations;
    }

    private static void addLineViolation(List<String> violations, Path path, int maxLines) {
        try {
            long lines = Files.lines(path).count();
            if (lines > maxLines) {
                violations.add(path + " has " + lines + " lines");
            }
        } catch (IOException e) {
            violations.add(path + " could not be read: " + e.getMessage());
        }
    }

    private static List<String> publicRecordViolations() throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> addRecordViolations(violations, p));
        }
        return violations;
    }

    private static void addRecordViolations(List<String> violations, Path path) {
        try {
            findPublicRecordComponentCounts(Files.readString(path))
                    .forEach(count -> addRecordViolation(violations, path, count));
        } catch (IOException e) {
            violations.add(path + " could not be read: " + e.getMessage());
        }
    }

    private static void addRecordViolation(List<String> violations, Path path, int count) {
        if (count > 5) {
            violations.add(path + " has public record with " + count + " components");
        }
    }

    private static List<Integer> findPublicRecordComponentCounts(String source) {
        var counts = new ArrayList<Integer>();
        int cursor = 0;
        while ((cursor = source.indexOf("public record ", cursor)) >= 0) {
            int open = source.indexOf('(', cursor);
            int close = matchingParen(source, open);
            counts.add(countComponents(source.substring(open + 1, close)));
            cursor = close + 1;
        }
        return counts;
    }

    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("unmatched record component list");
    }

    private static int countComponents(String components) {
        if (components.isBlank()) {
            return 0;
        }
        int count = 1;
        for (int i = 0, genericDepth = 0; i < components.length(); i++) {
            char c = components.charAt(i);
            if (c == '<') genericDepth++;
            if (c == '>') genericDepth--;
            if (c == ',' && genericDepth == 0) count++;
        }
        return count;
    }
}
