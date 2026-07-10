package ai.doctruth.internal.schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Build-time compatibility checks for caller-supplied JSON Schema. This does not
 * replace validation; it catches schema shapes DocTruth cannot safely resolve before
 * an extraction call reaches a provider.
 *
 * @hidden
 */
public final class JsonSchemaCompatibility {

    private JsonSchemaCompatibility() {
        throw new AssertionError("no instances");
    }

    public static List<String> check(JsonNode schema) {
        var errors = new ArrayList<String>();
        scan("$", schema, schema, errors);
        return List.copyOf(errors);
    }

    private static void scan(String path, JsonNode node, JsonNode root, List<String> errors) {
        if (!node.isObject()) {
            return;
        }
        if (node.has("$ref")) {
            checkRef(path, node.path("$ref").asText(), root, errors);
        }
        scanObject(path + ".$defs", node.path("$defs"), root, errors);
        scanObject(path + ".properties", node.path("properties"), root, errors);
        scanArray(path + ".anyOf", node.path("anyOf"), root, errors);
        scanArray(path + ".oneOf", node.path("oneOf"), root, errors);
        scanArray(path + ".allOf", node.path("allOf"), root, errors);
        if (node.has("items")) {
            scan(path + ".items", node.get("items"), root, errors);
        }
    }

    private static void checkRef(String path, String ref, JsonNode root, List<String> errors) {
        if (!ref.startsWith("#/")) {
            errors.add(path + " unsupported $ref " + ref);
            return;
        }
        if (root.at(ref.substring(1)).isMissingNode()) {
            errors.add(path + " unresolved $ref " + ref);
        }
    }

    private static void scanObject(String path, JsonNode object, JsonNode root, List<String> errors) {
        if (!object.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            scan(path + "." + entry.getKey(), entry.getValue(), root, errors);
        }
    }

    private static void scanArray(String path, JsonNode array, JsonNode root, List<String> errors) {
        if (!array.isArray()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            scan(path + "[" + i + "]", array.get(i), root, errors);
        }
    }
}
