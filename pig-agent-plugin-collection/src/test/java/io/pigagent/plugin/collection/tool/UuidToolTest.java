package io.pigagent.plugin.collection.tool;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** {@link UuidTool}: valid UUIDs, count handling, and canonical {"error"} on bad count. */
class UuidToolTest {

    private final UuidTool tool = new UuidTool();

    @Test
    void generateUuid_defaultCountIsOneValidUuid() {
        // Act
        String result = tool.generateUuid(null);

        // Assert — parses as a UUID (throws if not)
        assertThatCode(() -> UUID.fromString(result)).doesNotThrowAnyException();
    }

    @Test
    void generateUuid_countProducesThatManyLines() {
        // Act
        String result = tool.generateUuid(3);

        // Assert
        String[] lines = result.split("\n");
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThatCode(() -> UUID.fromString(line)).doesNotThrowAnyException();
        }
    }

    @Test
    void generateUuid_zeroCountReturnsError() {
        assertThat(tool.generateUuid(0)).startsWith("{\"error\":");
    }

    @Test
    void generateUuid_aboveMaxReturnsError() {
        assertThat(tool.generateUuid(101)).startsWith("{\"error\":");
    }
}
