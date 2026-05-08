package ai.doctruth.internal.schema;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds a compact JSON Schema from the caller's target type using Jackson's
 * bean/record introspection. Internal only; public API exposes only JsonNode.
 *
 * @hidden
 */
public final class JsonSchemaBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private JsonSchemaBuilder() {
        throw new AssertionError("no instances");
    }

    public static ObjectNode forType(Class<?> type) {
        return schemaFor(MAPPER.constructType(type));
    }

    private static ObjectNode schemaFor(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw)) {
            return typed("string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return typed("boolean");
        }
        if (isInteger(raw)) {
            return typed("integer");
        }
        if (isNumber(raw)) {
            return typed("number");
        }
        if (raw.isEnum()) {
            return enumSchema(raw);
        }
        if (Optional.class.isAssignableFrom(raw)) {
            return schemaFor(type.containedTypeOrUnknown(0));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arraySchema(type.containedTypeOrUnknown(0));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return typed("object");
        }
        return objectSchema(type);
    }

    private static ObjectNode objectSchema(JavaType type) {
        var schema = typed("object");
        ObjectNode properties = JSON.objectNode();
        ArrayNode required = JSON.arrayNode();
        var desc = MAPPER.getSerializationConfig().introspect(type);
        for (BeanPropertyDefinition prop : desc.findProperties()) {
            if (!prop.couldSerialize()) {
                continue;
            }
            properties.set(prop.getName(), schemaFor(prop.getPrimaryType()));
            required.add(prop.getName());
        }
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode arraySchema(JavaType itemType) {
        var schema = typed("array");
        schema.set("items", schemaFor(itemType));
        return schema;
    }

    private static ObjectNode enumSchema(Class<?> raw) {
        var schema = typed("string");
        ArrayNode values = JSON.arrayNode();
        for (Object c : raw.getEnumConstants()) {
            values.add(((Enum<?>) c).name());
        }
        schema.set("enum", values);
        return schema;
    }

    private static ObjectNode typed(String type) {
        var schema = JSON.objectNode();
        schema.put("type", type);
        return schema;
    }

    private static boolean isInteger(Class<?> raw) {
        return raw == byte.class
                || raw == short.class
                || raw == int.class
                || raw == long.class
                || raw == Byte.class
                || raw == Short.class
                || raw == Integer.class
                || raw == Long.class;
    }

    private static boolean isNumber(Class<?> raw) {
        return raw == float.class
                || raw == double.class
                || raw == Float.class
                || raw == Double.class
                || Number.class.isAssignableFrom(raw);
    }
}
