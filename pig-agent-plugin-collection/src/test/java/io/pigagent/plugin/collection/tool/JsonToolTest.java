package io.pigagent.plugin.collection.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link JsonTool}: pretty-print, validate, and canonical error handling. */
class JsonToolTest {

    private final JsonTool tool = new JsonTool();

    @Test
    void prettyPrint_reformatsCompactJson() {
        // Act
        String result = tool.jsonPrettyPrint("{\"a\":1,\"b\":[2,3]}");

        // Assert — indented multi-line, still valid, still an error-free result
        assertThat(result).doesNotStartWith("{\"error\":").contains("\n").contains("\"a\"");
        assertThat(tool.jsonValidate(result)).isEqualTo("{\"valid\":true}");
    }

    @Test
    void prettyPrint_invalidJsonReturnsError() {
        assertThat(tool.jsonPrettyPrint("{not valid")).startsWith("{\"error\":");
    }

    @Test
    void validate_validObjectReportsTrue() {
        assertThat(tool.jsonValidate("{\"x\":true}")).isEqualTo("{\"valid\":true}");
    }

    @Test
    void validate_invalidReportsFalseWithoutThrowing() {
        // Act
        String result = tool.jsonValidate("{bad json");

        // Assert
        assertThat(result).startsWith("{\"valid\":false");
    }
}
