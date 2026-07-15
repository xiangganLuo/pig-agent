package io.pigagent.plugin.builtin.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Base64Tool}: known vectors, round-trip, and canonical {"error"} on invalid input. */
class Base64ToolTest {

    private final Base64Tool tool = new Base64Tool();

    @Test
    void encode_knownVector() {
        assertThat(tool.base64Encode("hello")).isEqualTo("aGVsbG8=");
    }

    @Test
    void decode_knownVector() {
        assertThat(tool.base64Decode("aGVsbG8=")).isEqualTo("hello");
    }

    @Test
    void roundTrip_preservesUnicode() {
        String original = "pig-agent 中文 🐷";
        assertThat(tool.base64Decode(tool.base64Encode(original))).isEqualTo(original);
    }

    @Test
    void decode_invalidInputReturnsError() {
        assertThat(tool.base64Decode("!!!not-base64!!!")).startsWith("{\"error\":");
    }
}
