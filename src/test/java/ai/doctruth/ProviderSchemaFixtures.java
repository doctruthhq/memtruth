package ai.doctruth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ProviderSchemaFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderSchemaFixtures() {
        throw new AssertionError("no instances");
    }

    static JsonNode nestedPydanticSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "$defs": {
                        "Address": {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string", "minLength": 2 },
                            "postcode": { "type": "string", "pattern": "^[0-9]{4}$" }
                          },
                          "required": ["city", "postcode"],
                          "additionalProperties": false
                        }
                      },
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" },
                        "address": { "$ref": "#/$defs/Address" },
                        "nickname": {
                          "anyOf": [{ "type": "string" }, { "type": "null" }],
                          "default": null
                        }
                      },
                      "required": ["name", "address"],
                      "additionalProperties": false
                    }
                    """);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid fixture schema", e);
        }
    }
}
