package ai.doctruth.internal.schema;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson mapper for Java-native extraction targets. Kept internal so public API
 * remains framework-neutral while typed extraction can support JDK value shapes.
 *
 * @hidden
 */
public final class JavaNativeObjectMapper {

    private JavaNativeObjectMapper() {
        throw new AssertionError("no instances");
    }

    public static ObjectMapper create() {
        var module = new SimpleModule();
        module.addDeserializer(Optional.class, new OptionalDeserializer());
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(module);
    }

    private static final class OptionalDeserializer extends JsonDeserializer<Optional<?>>
            implements ContextualDeserializer {

        private final JavaType valueType;

        private OptionalDeserializer() {
            this(null);
        }

        private OptionalDeserializer(JavaType valueType) {
            this.valueType = valueType;
        }

        @Override
        public Optional<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JavaType activeType = valueType == null ? ctxt.constructType(Object.class) : valueType;
            return Optional.ofNullable(ctxt.readValue(p, activeType));
        }

        @Override
        public Optional<?> getNullValue(DeserializationContext ctxt) {
            return Optional.empty();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return Optional.empty();
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            if (property == null) {
                return new OptionalDeserializer(ctxt.constructType(Object.class));
            }
            return new OptionalDeserializer(property.getType().containedTypeOrUnknown(0));
        }
    }
}
