package ai.doctruth.internal.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaNativeObjectMapperTest {

    @Test
    @DisplayName("utility class cannot be instantiated")
    void utilityClassCannotBeInstantiated() throws Exception {
        var constructor = JavaNativeObjectMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .hasRootCauseMessage("no instances");
    }

    @Test
    @DisplayName("root Optional values keep absent/null/present semantics without a record property")
    void rootOptionalValuesDeserializeStrictly() throws Exception {
        var mapper = JavaNativeObjectMapper.create();
        var optionalString = new TypeReference<Optional<String>>() {};

        assertThat(mapper.readValue("null", optionalString)).isEqualTo(Optional.empty());
        assertThat(mapper.readValue("\"signed\"", optionalString)).isEqualTo(Optional.of("signed"));
    }
}
