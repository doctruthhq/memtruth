package ai.doctruth.internal.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ai.doctruth.ExtractionException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small local validator for the JSON Schema subset DocTruth generates and accepts for
 * structured extraction, including exported Pydantic schemas with local refs and
 * nullable unions.
 *
 * @hidden
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
        throw new AssertionError("no instances");
    }

    public static void validate(JsonNode value, JsonNode schema, int retries) throws ExtractionException {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(schema, "schema");
        var errors = new ArrayList<String>();
        validateNode("$", value, schema, schema, errors);
        if (!errors.isEmpty()) {
            throw new ExtractionException(
                    "EXTRACTION_SCHEMA_VALIDATION_FAILED",
                    "JSON Schema validation failed: " + String.join("; ", errors),
                    retries);
        }
    }

    private static void validateNode(String path, JsonNode value, JsonNode schema, JsonNode root, List<String> errors) {
        var resolved = resolveSchema(path, schema, root, errors);
        if (resolved.isEmpty()) {
            return;
        }
        var activeSchema = resolved.get();
        if (!validateCombinators(path, value, activeSchema, root, errors)) {
            return;
        }
        if (!JsonSchemaValueRules.matchesAllowedTypes(value, activeSchema.path("type"))) {
            errors.add(path + " expected " + JsonSchemaValueRules.expectedTypes(activeSchema.path("type")) + " but got "
                    + JsonSchemaValueRules.actualType(value));
            return;
        }
        validateEnum(path, value, activeSchema, errors);
        JsonSchemaValueRules.validateConstraints(path, value, activeSchema, errors);
        if (JsonSchemaValueRules.shouldValidateObject(value, activeSchema)) {
            validateObject(path, value, activeSchema, root, errors);
        }
        if (JsonSchemaValueRules.shouldValidateArray(value, activeSchema)) {
            validateArray(path, value, activeSchema, root, errors);
        }
    }

    private static Optional<JsonNode> resolveSchema(String path, JsonNode schema, JsonNode root, List<String> errors) {
        if (!schema.has("$ref")) {
            return Optional.of(schema);
        }
        String ref = schema.path("$ref").asText();
        if (!ref.startsWith("#/")) {
            errors.add(path + " unsupported $ref " + ref);
            return Optional.empty();
        }
        JsonNode resolved = root.at(ref.substring(1));
        if (resolved.isMissingNode()) {
            errors.add(path + " unresolved $ref " + ref);
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private static boolean validateCombinators(
            String path, JsonNode value, JsonNode schema, JsonNode root, List<String> errors) {
        validateAllOf(path, value, schema, root, errors);
        if (schema.path("anyOf").isArray() && !matchesAnyOf(path, value, schema.path("anyOf"), root)) {
            errors.add(path + " did not match anyOf");
            return false;
        }
        if (schema.path("oneOf").isArray() && !matchesOneOf(path, value, schema.path("oneOf"), root)) {
            errors.add(path + " did not match oneOf");
            return false;
        }
        return true;
    }

    private static void validateAllOf(
            String path, JsonNode value, JsonNode schema, JsonNode root, List<String> errors) {
        JsonNode allOf = schema.path("allOf");
        if (!allOf.isArray()) {
            return;
        }
        for (JsonNode branch : allOf) {
            validateNode(path, value, branch, root, errors);
        }
    }

    private static boolean matchesAnyOf(String path, JsonNode value, JsonNode branches, JsonNode root) {
        for (JsonNode branch : branches) {
            var branchErrors = new ArrayList<String>();
            validateNode(path, value, branch, root, branchErrors);
            if (branchErrors.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOneOf(String path, JsonNode value, JsonNode branches, JsonNode root) {
        int matches = 0;
        for (JsonNode branch : branches) {
            var branchErrors = new ArrayList<String>();
            validateNode(path, value, branch, root, branchErrors);
            matches += branchErrors.isEmpty() ? 1 : 0;
        }
        return matches == 1;
    }

    private static void validateObject(
            String path, JsonNode value, JsonNode schema, JsonNode root, List<String> errors) {
        var properties = schema.path("properties");
        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            if (!value.has(name)) {
                errors.add(pathFor(path, name) + " required field missing");
            }
        }
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                if (value.has(entry.getKey())) {
                    validateNode(
                            pathFor(path, entry.getKey()), value.get(entry.getKey()), entry.getValue(), root, errors);
                }
            });
        }
        if (!schema.path("additionalProperties").asBoolean(true) && properties.isObject()) {
            value.fieldNames().forEachRemaining(name -> {
                if (!properties.has(name)) {
                    errors.add(pathFor(path, name) + " additional property is not allowed");
                }
            });
        }
    }

    private static void validateArray(
            String path, JsonNode value, JsonNode schema, JsonNode root, List<String> errors) {
        JsonNode items = schema.path("items");
        if (items.isMissingNode()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(path + "[" + i + "]", value.get(i), items, root, errors);
        }
    }

    private static void validateEnum(String path, JsonNode value, JsonNode schema, List<String> errors) {
        JsonNode values = schema.path("enum");
        if (!values.isArray()) {
            return;
        }
        for (JsonNode allowed : values) {
            if (allowed.equals(value)) {
                return;
            }
        }
        errors.add(path + " must be one of " + values);
    }

    private static String pathFor(String parent, String child) {
        return "$".equals(parent) ? child : parent + "." + child;
    }
}
