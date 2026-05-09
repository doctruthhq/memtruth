package ai.doctruth.internal.schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
        return schemaFor(MAPPER.constructType(type), "$");
    }

    private static ObjectNode schemaFor(JavaType type, String path) {
        Class<?> raw = type.getRawClass();
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw)) {
            return typed("string");
        }
        if (raw == LocalDate.class) {
            return formattedString("date");
        }
        if (raw == Instant.class || raw == OffsetDateTime.class || raw == LocalDateTime.class) {
            return formattedString("date-time");
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
            return nullable(schemaFor(type.containedTypeOrUnknown(0), path));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arraySchema(type.containedTypeOrUnknown(0), path);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return mapSchema(type.containedTypeOrUnknown(1), path);
        }
        if (raw == Object.class) {
            throw unsupported(path, raw);
        }
        return objectSchema(type, path);
    }

    private static ObjectNode objectSchema(JavaType type, String path) {
        var schema = typed("object");
        ObjectNode properties = JSON.objectNode();
        ArrayNode required = JSON.arrayNode();
        var desc = MAPPER.getSerializationConfig().introspect(type);
        for (BeanPropertyDefinition prop : desc.findProperties()) {
            if (!prop.couldSerialize()) {
                continue;
            }
            var propertySchema = schemaFor(prop.getPrimaryType(), path + "." + prop.getName());
            properties.set(prop.getName(), propertySchema);
            if (!isOptional(prop.getPrimaryType())) {
                required.add(prop.getName());
            }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode arraySchema(JavaType itemType, String path) {
        var schema = typed("array");
        schema.set("items", schemaFor(itemType, path + "[]"));
        return schema;
    }

    private static ObjectNode mapSchema(JavaType valueType, String path) {
        var schema = typed("object");
        schema.set("additionalProperties", schemaFor(valueType, path + ".*"));
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

    private static ObjectNode formattedString(String format) {
        var schema = typed("string");
        schema.put("format", format);
        return schema;
    }

    private static boolean isOptional(JavaType type) {
        return Optional.class.isAssignableFrom(type.getRawClass());
    }

    private static ObjectNode nullable(ObjectNode schema) {
        var type = schema.path("type");
        if (!type.isTextual()) {
            return schema;
        }
        ArrayNode nullableType = JSON.arrayNode();
        nullableType.add(type.asText());
        nullableType.add("null");
        schema.set("type", nullableType);
        return schema;
    }

    private static IllegalArgumentException unsupported(String path, Class<?> raw) {
        return new IllegalArgumentException("unsupported Java schema type at " + path + ": " + raw.getName());
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
