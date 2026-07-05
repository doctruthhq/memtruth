package ai.doctruth.internal.citation;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import ai.doctruth.BoundingBox;
import ai.doctruth.Citation;
import ai.doctruth.CitationSource;
import ai.doctruth.FigureSection;
import ai.doctruth.ParsedDocument;
import ai.doctruth.ParsedSection;
import ai.doctruth.SourceLocation;
import ai.doctruth.TableSection;
import ai.doctruth.TextSection;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk extracted records, maps, lists, and JSON nodes; match each leaf field back to
 * source text via exact substring first, then Jaro-Winkler fuzzy windows. Weak matches
 * are logged and surfaced instead of being silently omitted.
 *
 * @hidden
 */
public final class CitationMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CitationMatcher.class);

    /** Default {@code minScore} per ADR 0005. */
    public static final double DEFAULT_MIN_SCORE = 0.85;

    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

    private final double minScore;

    public CitationMatcher() {
        this(DEFAULT_MIN_SCORE);
    }

    public CitationMatcher(double minScore) {
        if (Double.isNaN(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore must be a real number in [0.0, 1.0], got " + minScore);
        }
        this.minScore = minScore;
    }

    /**
     * Walk {@code value} and produce a citation per non-null, non-blank leaf, keyed by
     * JSON-pointer-ish field path.
     *
     * @throws NullPointerException if {@code value} or {@code doc} is null.
     */
    public Map<String, Citation> matchAll(Object value, ParsedDocument doc) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(doc, "doc");
        var leaves = new ArrayList<Leaf>();
        traverse("", value, leaves);
        var sections = renderedSections(doc);
        var fallback = new SourceLocation(1, 1, 1, 1, 0);
        var out = new LinkedHashMap<String, Citation>();
        for (var leaf : leaves) {
            out.put(leaf.path(), matchOne(leaf.value(), leaf.path(), sections, fallback));
        }
        return Map.copyOf(out);
    }

    private Citation matchOne(
            String needle, String path, List<RenderedCitationSection> sections, SourceLocation fallback) {
        for (var sec : sections) {
            int idx = sec.text().indexOf(needle);
            if (idx >= 0) {
                return citation(sec, needle, 1.0);
            }
        }
        var best = bestFuzzy(needle, sections);
        if (best == null) {
            LOG.warn("citation match unavailable: field={} score=0.0 threshold={}", path, minScore);
            return new Citation(fallback, needle, 0.0);
        }
        if (best.matchScore() < minScore) {
            LOG.warn(
                    "citation match below threshold: field={} score={} threshold={}",
                    path,
                    best.matchScore(),
                    minScore);
        }
        return best;
    }

    private static Citation bestFuzzy(String needle, List<RenderedCitationSection> sections) {
        Citation best = null;
        for (var sec : sections) {
            var c = bestFuzzyWindow(needle, sec);
            if (c == null) {
                continue;
            }
            if (best == null || c.matchScore() > best.matchScore()) {
                best = c;
            }
        }
        return best;
    }

    private static Citation bestFuzzyWindow(String needle, RenderedCitationSection sec) {
        String haystack = sec.text();
        if (haystack.isEmpty() || needle.isEmpty()) {
            return null;
        }
        int n = needle.length();
        int lo = Math.max(1, (int) Math.floor(n * 0.8));
        int hi = Math.min(haystack.length(), (int) Math.ceil(n * 1.2));
        var positions = candidatePositions(needle, haystack);
        double bestScore = -1.0;
        String bestQuote = null;
        for (int pos : positions) {
            for (int len = lo; len <= hi; len += Math.max(1, (hi - lo) / 2)) {
                int end = Math.min(haystack.length(), pos + len);
                if (end <= pos) {
                    continue;
                }
                String window = haystack.substring(pos, end);
                double score = JW.apply(needle, window);
                if (score > bestScore) {
                    bestScore = score;
                    bestQuote = window;
                }
            }
        }
        if (bestQuote == null || bestQuote.isBlank()) {
            return null;
        }
        return citation(sec, bestQuote, Math.max(0.0, Math.min(1.0, bestScore)));
    }

    private static Citation citation(RenderedCitationSection sec, String exactQuote, double matchScore) {
        return new Citation(
                sec.location(),
                exactQuote,
                matchScore,
                sec.boundingBox(),
                Optional.of(new CitationSource(sec.sourceDocId(), sec.sourceUnitId())));
    }

    private static List<Integer> candidatePositions(String needle, String haystack) {
        var out = new ArrayList<Integer>();
        out.add(0);
        String prefix = needle.substring(0, Math.min(5, needle.length()));
        if (!prefix.isBlank()) {
            int from = 0;
            while (from <= haystack.length() - prefix.length()) {
                int idx = haystack.indexOf(prefix, from);
                if (idx < 0) {
                    break;
                }
                out.add(idx);
                from = idx + 1;
                if (out.size() > 8) {
                    break;
                }
            }
        }
        int stride = Math.max(8, haystack.length() / 8);
        for (int p = stride; p < haystack.length(); p += stride) {
            out.add(p);
        }
        return out;
    }

    private static List<RenderedCitationSection> renderedSections(ParsedDocument doc) {
        var out = new ArrayList<RenderedCitationSection>(doc.sections().size());
        for (int i = 0; i < doc.sections().size(); i++) {
            var s = doc.sections().get(i);
            out.add(new RenderedCitationSection(
                    textOf(s), locationOf(s), boundingBoxOf(s), doc.docId(), "u" + (i + 1)));
        }
        return out;
    }

    private static String textOf(ParsedSection s) {
        return switch (s) {
            case TextSection ts -> ts.text();
            case TableSection ts -> {
                var sb = new StringBuilder();
                for (var row : ts.rows()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(String.join(" | ", row));
                }
                yield sb.toString();
            }
            case FigureSection fs -> "[Figure: " + fs.caption() + "]";
        };
    }

    private static SourceLocation locationOf(ParsedSection s) {
        return switch (s) {
            case TextSection ts -> ts.location();
            case TableSection ts -> ts.location();
            case FigureSection fs -> fs.location();
        };
    }

    private static Optional<BoundingBox> boundingBoxOf(ParsedSection s) {
        return switch (s) {
            case TextSection ts -> ts.boundingBox();
            case TableSection ignored -> Optional.empty();
            case FigureSection ignored -> Optional.empty();
        };
    }

    private static void traverse(String path, Object node, List<Leaf> out) {
        if (node == null) {
            return;
        }
        if (node instanceof Optional<?> opt) {
            opt.ifPresent(v -> traverse(path, v, out));
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                traverse(path + "[" + i + "]", list.get(i), out);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                traverse(joinPath(path, String.valueOf(e.getKey())), e.getValue(), out);
            }
            return;
        }
        if (node instanceof JsonNode json) {
            traverseJson(path, json, out);
            return;
        }
        if (node.getClass().isRecord()) {
            traverseRecord(path, node, out);
            return;
        }
        var leaf = String.valueOf(node);
        if (leaf == null || leaf.isBlank()) {
            return;
        }
        out.add(new Leaf(path, leaf));
    }

    private static void traverseJson(String path, JsonNode node, List<Leaf> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> traverseJson(joinPath(path, e.getKey()), e.getValue(), out));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                traverseJson(path + "[" + i + "]", node.get(i), out);
            }
            return;
        }
        String leaf = node.isTextual() ? node.asText() : node.toString();
        if (!leaf.isBlank()) {
            out.add(new Leaf(path, leaf));
        }
    }

    private static void traverseRecord(String path, Object record, List<Leaf> out) {
        RecordComponent[] comps = record.getClass().getRecordComponents();
        for (var c : comps) {
            Object v;
            try {
                var accessor = c.getAccessor();
                // Handles test records with package-private enclosing classes.
                accessor.setAccessible(true);
                v = accessor.invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "failed to read record component " + c.getName() + " on " + record.getClass(), e);
            }
            traverse(joinPath(path, c.getName()), v, out);
        }
    }

    private static String joinPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private record Leaf(String path, String value) {}
}
