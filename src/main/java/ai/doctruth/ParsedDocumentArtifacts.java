package ai.doctruth;

import java.util.List;
import java.util.Optional;

final class ParsedDocumentArtifacts {

    private static final IdentityWeakStore<ParsedDocument, List<DiscardedBlock>> DISCARDED = new IdentityWeakStore<>();

    private ParsedDocumentArtifacts() {
        throw new AssertionError("no instances");
    }

    static void attachDiscardedBlocks(ParsedDocument document, List<DiscardedBlock> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        DISCARDED.put(document, List.copyOf(blocks));
    }

    static Optional<List<DiscardedBlock>> discardedBlocks(ParsedDocument document) {
        return DISCARDED.get(document);
    }
}
