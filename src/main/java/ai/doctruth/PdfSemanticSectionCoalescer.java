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

    private PdfSemanticSectionCoalescer() {
        throw new AssertionError("no instances");
    }

    static List<PdfTextBlock> coalesce(List<PdfTextBlock> blocks) {
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
