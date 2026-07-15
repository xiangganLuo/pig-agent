package io.pigagent.plugin.builtin.tool;

import io.pigagent.tool.availability.Availability;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** webSearch availability judged by the BRAVE_API_KEY env var; the reason names the var, not its value. */
class BraveWebSearchToolAvailabilityTest {

    @Test
    void availableWhenApiKeyPresent() {
        Function<String, String> env = Map.of("BRAVE_API_KEY", "secret-value-123")::get;
        BraveWebSearchTool tool = new BraveWebSearchTool(env);

        Availability a = tool.checkAvailability();

        assertThat(a.available()).isTrue();
        assertThat(a.reason()).isNull();
    }

    @Test
    void unavailableWhenApiKeyMissing() {
        BraveWebSearchTool tool = new BraveWebSearchTool(name -> null);

        Availability a = tool.checkAvailability();

        assertThat(a.available()).isFalse();
        assertThat(a.reason()).isEqualTo("BRAVE_API_KEY not set");
    }

    @Test
    void unavailableWhenApiKeyBlank() {
        BraveWebSearchTool tool = new BraveWebSearchTool(name -> "   ");
        assertThat(tool.checkAvailability().available()).isFalse();
    }

    @Test
    void reasonNeverLeaksTheCredentialValue() {
        Function<String, String> env = Map.of("BRAVE_API_KEY", "super-secret-token")::get;
        BraveWebSearchTool tool = new BraveWebSearchTool(env);
        // Present → available, no reason at all; and if we probe the missing case the reason is a name.
        assertThat(tool.checkAvailability().reason()).isNull();
        BraveWebSearchTool missing = new BraveWebSearchTool(name -> null);
        assertThat(missing.checkAvailability().reason()).doesNotContain("super-secret-token");
    }

    @Test
    void governsTheWebSearchToolName() {
        assertThat(new BraveWebSearchTool(name -> null).availabilityToolNames()).containsExactly("webSearch");
    }
}
