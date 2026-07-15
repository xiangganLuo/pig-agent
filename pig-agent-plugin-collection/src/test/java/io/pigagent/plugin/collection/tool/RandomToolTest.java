package io.pigagent.plugin.collection.tool;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RandomTool}: bounds respected, error paths, and charset selection. */
class RandomToolTest {

    private final RandomTool tool = new RandomTool();

    @Test
    void randomNumber_singletonRangeReturnsThatValue() {
        assertThat(tool.randomNumber(5, 5)).isEqualTo("5");
    }

    @RepeatedTest(50)
    void randomNumber_staysWithinInclusiveRange() {
        long value = Long.parseLong(tool.randomNumber(1, 10));
        assertThat(value).isBetween(1L, 10L);
    }

    @Test
    void randomNumber_minGreaterThanMaxReturnsError() {
        assertThat(tool.randomNumber(10, 1)).startsWith("{\"error\":");
    }

    @Test
    void randomString_defaultCharsetHasRequestedLength() {
        String result = tool.randomString(16, null);
        assertThat(result).hasSize(16).matches("[A-Za-z0-9]+");
    }

    @RepeatedTest(20)
    void randomString_numericCharsetIsAllDigits() {
        assertThat(tool.randomString(12, "numeric")).matches("[0-9]{12}");
    }

    @Test
    void randomString_zeroLengthReturnsError() {
        assertThat(tool.randomString(0, "alphanumeric")).startsWith("{\"error\":");
    }

    @Test
    void randomString_invalidCharsetReturnsError() {
        assertThat(tool.randomString(4, "klingon")).startsWith("{\"error\":");
    }
}
