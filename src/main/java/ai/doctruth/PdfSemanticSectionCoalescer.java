package ai.doctruth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PdfSemanticSectionCoalescer {

    private static final double COLUMN_OVERLAP_RATIO = 0.12;
    private static final double COLUMN_CENTER_TOLERANCE = 180.0;
    private static final double BELOW_HEADING_TOLERANCE = 8.0;
    private static final double SPLIT_TITLE_ALIGNMENT_TOLERANCE = 24.0;
    private static final double SPLIT_TITLE_HORIZONTAL_GAP = 24.0;
    private static final double SPLIT_TITLE_OVERLAP_RATIO = 0.50;
    private static final double SPLIT_TITLE_VERTICAL_GAP = 12.0;

    private PdfSemanticSectionCoalescer() {
        throw new AssertionError("no instances");
    }

    static List<PdfTextBlock> coalesce(List<PdfTextBlock> blocks) {
        blocks = reconstructSplitSectionTitles(blocks);
        var anchors = semanticAnchors(blocks);
        if (anchors.isEmpty()) {
            return blocks;
        }
        var grouped = new HashMap<SectionAnchor, List<PdfTextBlock>>();
        var unassigned = new ArrayList<PdfTextBlock>();
        for (var anchor : anchors) {
            grouped.put(anchor, new ArrayList<>(List.of(anchor.block())));
        }
        var assigned = new HashMap<PdfTextBlock, SectionAnchor>();
        for (var block : blocks) {
            if (startsSemanticSection(block)) {
                continue;
            }
            var owner = ownerFor(block, anchors);
            if (owner.isPresent()) {
                var anchor = owner.orElseThrow();
                grouped.get(anchor).add(block);
                assigned.put(block, anchor);
            } else {
                unassigned.add(block);
            }
        }
        attachSameRowSectionValues(unassigned, grouped, assigned);
        var out = new ArrayList<PdfTextBlock>();
        out.addAll(unassigned);
        for (var anchor : anchors) {
            out.add(PdfTextBlockGeometry.merge(grouped.get(anchor)));
        }
        out.sort(PdfTextBlockGeometry::compareTopLeft);
        return attachOrphanRowValues(out);
    }

    private static List<PdfTextBlock> reconstructSplitSectionTitles(List<PdfTextBlock> blocks) {
        var out = new ArrayList<PdfTextBlock>();
        for (int i = 0; i < blocks.size(); i++) {
            var current = blocks.get(i);
            if (i + 1 < blocks.size() && canMergeSplitSectionTitle(current, blocks.get(i + 1))) {
                out.add(mergeSplitSectionTitle(current, blocks.get(++i)));
            } else {
                out.add(current);
            }
        }
        return out;
    }

    private static boolean canMergeSplitSectionTitle(PdfTextBlock first, PdfTextBlock second) {
        if (first.boundingBox().isEmpty() || second.boundingBox().isEmpty()) {
            return false;
        }
        if (first.text().contains("\n") || second.text().contains("\n") || !samePage(first, second)) {
            return false;
        }
        String merged = first.text().strip() + " " + second.text().strip();
        return PdfResumeSectionNames.isKnown(merged)
                && (first.kind() == BlockKind.HEADING || second.kind() == BlockKind.HEADING)
                && splitTitleFragmentsFit(first, second);
    }

    private static boolean samePage(PdfTextBlock first, PdfTextBlock second) {
        return first.location().pageStart() == second.location().pageStart()
                && first.location().pageEnd() == second.location().pageEnd();
    }

    private static boolean splitTitleFragmentsFit(PdfTextBlock first, PdfTextBlock second) {
        var a = first.boundingBox().orElseThrow();
        var b = second.boundingBox().orElseThrow();
        return stackedTitleFragments(a, b) || sameRowTitleFragments(a, b);
    }

    private static boolean stackedTitleFragments(BoundingBox first, BoundingBox second) {
        double gap = second.y0() - first.y1();
        if (gap < 0.0 || gap > SPLIT_TITLE_VERTICAL_GAP) {
            return false;
        }
        return splitTitleAligned(first, second) || horizontalOverlapRatio(first, second) >= SPLIT_TITLE_OVERLAP_RATIO;
    }

    private static boolean sameRowTitleFragments(BoundingBox first, BoundingBox second) {
        return verticalOverlapRatio(first, second) >= 0.45
                && horizontalGap(first, second) <= SPLIT_TITLE_HORIZONTAL_GAP;
    }

    private static boolean splitTitleAligned(BoundingBox first, BoundingBox second) {
        return Math.abs(first.x0() - second.x0()) <= SPLIT_TITLE_ALIGNMENT_TOLERANCE
                || Math.abs(PdfTextBlockGeometry.centerX(first) - PdfTextBlockGeometry.centerX(second))
                        <= SPLIT_TITLE_ALIGNMENT_TOLERANCE;
    }

    private static double horizontalOverlapRatio(BoundingBox first, BoundingBox second) {
        double overlap = Math.max(0.0, Math.min(first.x1(), second.x1()) - Math.max(first.x0(), second.x0()));
        double minWidth = Math.max(1.0, Math.min(first.x1() - first.x0(), second.x1() - second.x0()));
        return overlap / minWidth;
    }

    private static double verticalOverlapRatio(BoundingBox first, BoundingBox second) {
        double overlap = Math.max(0.0, Math.min(first.y1(), second.y1()) - Math.max(first.y0(), second.y0()));
        double minHeight = Math.max(1.0, Math.min(first.y1() - first.y0(), second.y1() - second.y0()));
        return overlap / minHeight;
    }

    private static double horizontalGap(BoundingBox first, BoundingBox second) {
        if (first.x1() <= second.x0()) {
            return second.x0() - first.x1();
        }
        if (second.x1() <= first.x0()) {
            return first.x0() - second.x1();
        }
        return 0.0;
    }

    private static PdfTextBlock mergeSplitSectionTitle(PdfTextBlock first, PdfTextBlock second) {
        var loc = new SourceLocation(
                first.location().pageStart(),
                second.location().pageEnd(),
                first.location().lineStart(),
                Math.max(first.location().lineEnd(), second.location().lineEnd()),
                first.location().charOffset());
        return new PdfTextBlock(
                first.text().strip() + " " + second.text().strip(),
                BlockKind.HEADING,
                loc,
                union(first, second));
    }

    private static void attachSameRowSectionValues(
            List<PdfTextBlock> unassigned,
            Map<SectionAnchor, List<PdfTextBlock>> grouped,
            Map<PdfTextBlock, SectionAnchor> assigned) {
        var attached = new ArrayList<PdfTextBlock>();
        for (var block : unassigned) {
            var owner = sameRowOwner(block, assigned);
            if (owner.isPresent()) {
                grouped.get(owner.orElseThrow()).add(block);
                attached.add(block);
            }
        }
        unassigned.removeAll(attached);
    }

    private static Optional<SectionAnchor> sameRowOwner(
            PdfTextBlock block, Map<PdfTextBlock, SectionAnchor> assigned) {
        SectionAnchor best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        for (var entry : assigned.entrySet()) {
            var peer = entry.getKey();
            if (!PdfResumeSectionNames.isRowValueSection(firstLine(entry.getValue().block())) || !sameRowValuePeer(peer, block)
                    || !isRightSideValue(peer, block) || !PdfResumeSectionNames.isCompactRowValue(block.text())) {
                continue;
            }
            double gap = PdfTextBlockGeometry.horizontalGap(peer, block);
            if (gap <= 160.0 && gap < bestGap) {
                best = entry.getValue();
                bestGap = gap;
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<PdfTextBlock> attachOrphanRowValues(List<PdfTextBlock> blocks) {
        var consumed = new boolean[blocks.size()];
        var out = new ArrayList<PdfTextBlock>();
        for (int i = 0; i < blocks.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            var block = blocks.get(i);
            if (!startsSemanticSection(block) || !PdfResumeSectionNames.isRowValueSection(firstLine(block))) {
                out.add(block);
                continue;
            }
            var rowValues = new ArrayList<PdfTextBlock>();
            rowValues.add(block);
            for (int j = 0; j < blocks.size(); j++) {
                if (j == i || consumed[j]) {
                    continue;
                }
                var candidate = blocks.get(j);
                if (startsSemanticSection(candidate) || !candidate.boundingBox().isPresent()
                        || !PdfResumeSectionNames.isCompactRowValue(candidate.text())) {
                    continue;
                }
                if (sameRowValuePeer(block, candidate) && isRightSideValue(block, candidate)) {
                    rowValues.add(candidate);
                    consumed[j] = true;
                }
            }
            out.add(PdfTextBlockGeometry.merge(rowValues));
        }
        out.sort(PdfTextBlockGeometry::compareTopLeft);
        return out;
    }

    private static List<SectionAnchor> semanticAnchors(List<PdfTextBlock> blocks) {
        var out = new ArrayList<SectionAnchor>();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            if (startsSemanticSection(block) && block.boundingBox().isPresent()) {
                out.add(new SectionAnchor(block));
            }
        }
        out.sort(Comparator.comparingDouble(SectionAnchor::top).thenComparingDouble(SectionAnchor::left));
        return out;
    }

    private static Optional<SectionAnchor> ownerFor(PdfTextBlock block, List<SectionAnchor> anchors) {
        if (block.boundingBox().isEmpty()) {
            return Optional.empty();
        }
        SectionAnchor best = null;
        for (var anchor : anchors) {
            if (!isBelow(anchor, block) || !belongsToAnchorLane(anchor.block(), block) || hasLowerSameColumnAnchor(anchor, block, anchors)) {
                continue;
            }
            if (best == null || anchor.top() > best.top()) {
                best = anchor;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isBelow(SectionAnchor anchor, PdfTextBlock block) {
        return PdfTextBlockGeometry.top(block) >= anchor.top() - BELOW_HEADING_TOLERANCE;
    }

    private static boolean hasLowerSameColumnAnchor(
            SectionAnchor anchor, PdfTextBlock block, List<SectionAnchor> anchors) {
        double blockTop = PdfTextBlockGeometry.top(block);
        for (var other : anchors) {
            if (other == anchor || !sameVisualColumn(anchor.block(), other.block())) {
                continue;
            }
            if (other.top() > anchor.top() && other.top() <= blockTop + BELOW_HEADING_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private static boolean belongsToAnchorLane(PdfTextBlock anchor, PdfTextBlock block) {
        if (block.kind() == BlockKind.HEADING) {
            return sameHeadingLane(anchor, block);
        }
        return sameVisualColumn(anchor, block);
    }

    private static boolean sameHeadingLane(PdfTextBlock left, PdfTextBlock right) {
        if (left.boundingBox().isEmpty() || right.boundingBox().isEmpty()) {
            return false;
        }
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        double overlap = Math.max(0.0, Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0()));
        double minWidth = Math.max(1.0, Math.min(a.x1() - a.x0(), b.x1() - b.x0()));
        if (overlap / minWidth >= COLUMN_OVERLAP_RATIO) {
            return true;
        }
        return Math.abs(a.x0() - b.x0()) <= 72.0;
    }

    private static boolean sameVisualColumn(PdfTextBlock left, PdfTextBlock right) {
        if (left.boundingBox().isEmpty() || right.boundingBox().isEmpty()) {
            return false;
        }
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        double overlap = Math.max(0.0, Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0()));
        double minWidth = Math.max(1.0, Math.min(a.x1() - a.x0(), b.x1() - b.x0()));
        if (overlap / minWidth >= COLUMN_OVERLAP_RATIO) {
            return true;
        }
        return Math.abs(PdfTextBlockGeometry.centerX(a) - PdfTextBlockGeometry.centerX(b)) <= COLUMN_CENTER_TOLERANCE;
    }

    private static Optional<BoundingBox> union(PdfTextBlock first, PdfTextBlock second) {
        var a = first.boundingBox().orElseThrow();
        var b = second.boundingBox().orElseThrow();
        return Optional.of(new BoundingBox(
                Math.min(a.x0(), b.x0()),
                Math.min(a.y0(), b.y0()),
                Math.max(a.x1(), b.x1()),
                Math.max(a.y1(), b.y1())));
    }

    private static boolean startsSemanticSection(PdfTextBlock block) {
        if (block.kind() == BlockKind.HEADING) {
            return PdfResumeSectionNames.isKnown(firstLine(block));
        }
        return false;
    }

    private static String firstLine(PdfTextBlock block) {
        return block.text().lines().findFirst().orElse("").strip();
    }

    private static boolean sameRowValuePeer(PdfTextBlock left, PdfTextBlock right) {
        if (PdfTextBlockGeometry.sameRow(left, right)) {
            return true;
        }
        if (left.boundingBox().isEmpty() || right.boundingBox().isEmpty()) {
            return false;
        }
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        double verticalOverlap = Math.max(0.0, Math.min(a.y1(), b.y1()) - Math.max(a.y0(), b.y0()));
        double minHeight = Math.max(1.0, Math.min(a.y1() - a.y0(), b.y1() - b.y0()));
        return verticalOverlap / minHeight >= 0.20 || Math.abs(a.y0() - b.y0()) <= 32.0 || Math.abs(a.y1() - b.y1()) <= 32.0;
    }

    private static boolean isRightSideValue(PdfTextBlock left, PdfTextBlock right) {
        if (PdfTextBlockGeometry.isToRightOf(left, right)) {
            return true;
        }
        var a = left.boundingBox().orElseThrow();
        var b = right.boundingBox().orElseThrow();
        return PdfTextBlockGeometry.centerX(b) > PdfTextBlockGeometry.centerX(a) + 24.0;
    }

    private record SectionAnchor(PdfTextBlock block) {
        double top() {
            return PdfTextBlockGeometry.top(block);
        }

        double left() {
            return PdfTextBlockGeometry.left(block);
        }
    }
}
