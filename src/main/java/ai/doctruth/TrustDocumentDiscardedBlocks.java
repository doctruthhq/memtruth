package ai.doctruth;

import java.util.List;
import java.util.Optional;

final class TrustDocumentDiscardedBlocks {

    private static final IdentityWeakStore<TrustDocument, List<DiscardedBlock>> BLOCKS = new IdentityWeakStore<>();

    private TrustDocumentDiscardedBlocks() {
        throw new AssertionError("no instances");
    }

    static void attach(TrustDocument document, List<DiscardedBlock> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        BLOCKS.put(document, List.copyOf(blocks));
    }

    static Optional<List<DiscardedBlock>> forDocument(TrustDocument document) {
        return BLOCKS.get(document);
    }
}
