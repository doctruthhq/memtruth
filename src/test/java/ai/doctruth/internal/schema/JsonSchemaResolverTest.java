package ai.doctruth.internal.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSchemaResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void resolvesLocalRefsRecursively() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "$defs": {
                    "alias": { "$ref": "#/$defs/party" },
                    "party": {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" }
                      }
                    }
                  },
                  "properties": {
                    "party": { "$ref": "#/$defs/alias" }
                  }
                }
                """);

        var resolved = JsonSchemaResolver.resolveLocal(root.at("/properties/party"), root);

        assertThat(resolved.path("type").asText()).isEqualTo("object");
        assertThat(resolved.path("properties").has("name")).isTrue();
    }

    @Test
    void returnsOriginalSchemaForRefsOutsideSupportedLocalSubset() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "$defs": {
                    "cycle": { "$ref": "#/$defs/cycle" }
                  },
                  "remote": { "$ref": "https://example.invalid/schema.json" },
                  "missing": { "$ref": "#/$defs/missing" }
                }
                """);

        assertThat(JsonSchemaResolver.resolveLocal(root.path("remote"), root)).isSameAs(root.path("remote"));
        assertThat(JsonSchemaResolver.resolveLocal(root.path("missing"), root)).isSameAs(root.path("missing"));
        assertThat(JsonSchemaResolver.resolveLocal(root.at("/$defs/cycle"), root))
                .isSameAs(root.at("/$defs/cycle"));
    }

    @Test
    void rejectsNullInputs() throws Exception {
        var schema = MAPPER.readTree("{}");

        assertThatThrownBy(() -> JsonSchemaResolver.resolveLocal(null, schema))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> JsonSchemaResolver.resolveLocal(schema, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("root");
    }
}
