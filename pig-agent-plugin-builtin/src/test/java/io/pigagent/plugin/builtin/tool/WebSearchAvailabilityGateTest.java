package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.availability.ToolAvailabilityGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After extraction into {@code pig-agent-plugin-builtin}, {@code webSearch} MUST keep its availability
 * gate: with no {@code BRAVE_API_KEY} the tool is removed from the {@link Toolkit} (never enters the
 * model schema) and the report names only the missing variable, never a credential value.
 */
class WebSearchAvailabilityGateTest {

    private static Toolkit toolkitWith(Object tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(tool).apply();
        return toolkit;
    }

    @Test
    void webSearchHiddenFromToolkitWhenApiKeyMissing() {
        // Arrange — a webSearch tool whose env lookup returns no key
        BraveWebSearchTool tool = new BraveWebSearchTool(name -> null);
        Toolkit toolkit = toolkitWith(tool);
        assertThat(toolkit.getToolNames()).contains("webSearch");

        // Act — the availability gate evaluates the plugin tool instances
        ToolAvailabilityReport report = ToolAvailabilityGate.applyTo(toolkit, List.of(tool));

        // Assert — removed from the schema, reported with a credential-free reason
        assertThat(toolkit.getToolNames()).doesNotContain("webSearch");
        assertThat(report.isEmpty()).isFalse();
        assertThat(report.hidden()).anySatisfy(h -> {
            assertThat(h.toolName()).isEqualTo("webSearch");
            assertThat(h.reason()).contains("BRAVE_API_KEY");
        });
    }

    @Test
    void webSearchRetainedWhenApiKeyPresent() {
        // Arrange — env supplies a key
        Function<String, String> env = name -> "BRAVE_API_KEY".equals(name) ? "secret-token" : null;
        BraveWebSearchTool tool = new BraveWebSearchTool(env);
        Toolkit toolkit = toolkitWith(tool);

        // Act
        ToolAvailabilityReport report = ToolAvailabilityGate.applyTo(toolkit, List.of(tool));

        // Assert — kept in the toolkit, nothing hidden, no credential echoed
        assertThat(toolkit.getToolNames()).contains("webSearch");
        assertThat(report.isEmpty()).isTrue();
    }
}
