package ai.doctruth.internal.schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON Schema value-type and scalar constraint rules used by the local
 * structured-output validator.
 *
 * @hidden
 */
final class JsonSchemaValueRules {

    private JsonSchemaValueRules() {
        throw new AssertionError("no instances");
    }

    static void validateConstraints(String path, JsonNode value, JsonNode schema, List<String> errors) {
        if (value.isTextual()) {
            validateStringConstraints(path, value.asText(), schema, errors);
        }
        if (value.isNumber()) {
            validateNumberConstraints(path, value.decimalValue(), schema, errors);
        }
        if (value.isArray()) {
            validateArrayConstraints(path, value, schema, errors);
        }
    }

    static boolean shouldValidateObject(JsonNode value, JsonNode schema) {
        return value.isObject()
                && (schema.has("properties") || schema.has("required") || schema.has("additionalProperties"));
    }

    static boolean shouldValidateArray(JsonNode value, JsonNode schema) {
        return value.isArray() && schema.has("items");
    }

    static boolean matchesAllowedTypes(JsonNode value, JsonNode typeNode) {
        if (typeNode.isMissingNode()) {
            return true;
        }
        if (typeNode.isTextual()) {
            return matchesType(value, typeNode.asText());
        }
        if (!typeNode.isArray()) {
            return true;
        }
        for (JsonNode type : typeNode) {
            if (type.isTextual() && matchesType(value, type.asText())) {
                return true;
            }
        }
        return false;
    }

    static String expectedTypes(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        var types = new ArrayList<String>();
        typeNode.forEach(type -> types.add(type.asText()));
        return String.join(" or ", types);
    }

    static String actualType(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return value.isNull() ? "null" : value.getNodeType().name().toLowerCase();
    }

    private static void validateStringConstraints(String path, String value, JsonNode schema, List<String> errors) {
        validateLength(path, "minLength", value.length(), schema, errors);
        validateLength(path, "maxLength", value.length(), schema, errors);
        validatePattern(path, value, schema, errors);
    }

    private static void validateLength(String path, String keyword, int actual, JsonNode schema, List<String> errors) {
        if (!schema.path(keyword).isIntegralNumber()) {
            return;
        }
        int expected = schema.path(keyword).asInt();
        if (violatesLength(keyword, actual, expected)) {
            errors.add(path + " violates " + keyword + " " + expected);
        }
    }

    private static boolean violatesLength(String keyword, int actual, int expected) {
        return ("minLength".equals(keyword) && actual < expected) || ("maxLength".equals(keyword) && actual > expected);
    }

    private static void validatePattern(String path, String value, JsonNode schema, List<String> errors) {
        if (!schema.path("pattern").isTextual()) {
            return;
        }
        try {
            if (!Pattern.compile(schema.path("pattern").asText()).matcher(value).find()) {
                errors.add(path + " violates pattern " + schema.path("pattern").asText());
            }
        } catch (PatternSyntaxException ex) {
            errors.add(path + " has invalid pattern " + schema.path("pattern").asText());
        }
    }

    private static void validateNumberConstraints(String path, BigDecimal value, JsonNode schema, List<String> errors) {
        validateNumberBound(path, "minimum", value, schema, errors);
        validateNumberBound(path, "maximum", value, schema, errors);
        validateNumberBound(path, "exclusiveMinimum", value, schema, errors);
        validateNumberBound(path, "exclusiveMaximum", value, schema, errors);
    }

    private static void validateNumberBound(
            String path, String keyword, BigDecimal value, JsonNode schema, List<String> errors) {
        if (!schema.path(keyword).isNumber()) {
            return;
        }
        var bound = schema.path(keyword).decimalValue();
        if (violatesNumberBound(keyword, value.compareTo(bound))) {
            errors.add(path + " violates " + keyword + " " + bound);
        }
    }

    private static boolean violatesNumberBound(String keyword, int comparison) {
        return switch (keyword) {
            case "minimum" -> comparison < 0;
            case "maximum" -> comparison > 0;
            case "exclusiveMinimum" -> comparison <= 0;
            case "exclusiveMaximum" -> comparison >= 0;
            default -> false;
        };
    }

    private static void validateArrayConstraints(String path, JsonNode value, JsonNode schema, List<String> errors) {
        validateItemCount(path, "minItems", value.size(), schema, errors);
        validateItemCount(path, "maxItems", value.size(), schema, errors);
    }

    private static void validateItemCount(
            String path, String keyword, int actual, JsonNode schema, List<String> errors) {
        if (!schema.path(keyword).isIntegralNumber()) {
            return;
        }
        int expected = schema.path(keyword).asInt();
        if (violatesItemCount(keyword, actual, expected)) {
            errors.add(path + " violates " + keyword + " " + expected);
        }
    }

    private static boolean violatesItemCount(String keyword, int actual, int expected) {
        return ("minItems".equals(keyword) && actual < expected) || ("maxItems".equals(keyword) && actual > expected);
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
