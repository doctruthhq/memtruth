package ai.doctruth;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import com.fasterxml.jackson.databind.JsonNode;

final class TrustDocumentLayeredOutputs {

    private static final Map<TrustDocument, LayeredOutputs> OUTPUTS = new WeakHashMap<>();

    private TrustDocumentLayeredOutputs() {
        throw new AssertionError("no instances");
    }

    static void attach(TrustDocument document, JsonNode contentBlocks, JsonNode parseTrace) {
        if ((contentBlocks == null || contentBlocks.isMissingNode())
                && (parseTrace == null || parseTrace.isMissingNode())) {
            return;
        }
        synchronized (OUTPUTS) {
            OUTPUTS.put(document, new LayeredOutputs(copy(contentBlocks), copy(parseTrace)));
        }
    }

    static Optional<JsonNode> contentBlocks(TrustDocument document) {
        return outputs(document).map(LayeredOutputs::contentBlocks).map(JsonNode::deepCopy);
    }

    static Optional<JsonNode> parseTrace(TrustDocument document) {
        return outputs(document).map(LayeredOutputs::parseTrace).map(JsonNode::deepCopy);
    }

    private static Optional<LayeredOutputs> outputs(TrustDocument document) {
        synchronized (OUTPUTS) {
            return Optional.ofNullable(OUTPUTS.get(document));
        }
    }

    private static JsonNode copy(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private record LayeredOutputs(JsonNode contentBlocks, JsonNode parseTrace) {}
}
