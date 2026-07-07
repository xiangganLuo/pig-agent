package io.pigagent.tool.availability;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.availability.ToolAvailabilityReport.Hidden;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Availability gate: evaluation (available / unavailable / exception fail-safe / no-interface default)
 * and schema filtering (unavailable tools removed from the Toolkit; hidden list correct and free of
 * credential values).
 */
class ToolAvailabilityGateTest {

    /** A tool that gates itself and its declared names on a fixed {@link Availability}. */
    private record FixedTool(Set<String> names, Availability result) implements ToolAvailability {
        @Override
        public Set<String> availabilityToolNames() {
            return names;
        }

        @Override
        public Availability checkAvailability() {
            return result;
        }
    }

    private record ThrowingTool(Set<String> names) implements ToolAvailability {
        @Override
        public Set<String> availabilityToolNames() {
            return names;
        }

        @Override
        public Availability checkAvailability() {
            throw new IllegalStateException("boom");
        }
    }

    /** A plain tool with no availability declaration — always available. */
    private static final class PlainTool {
    }

    @Test
    void availableToolIsNotHidden() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(
                List.of(new FixedTool(Set.of("webSearch"), Availability.AVAILABLE)));
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void unavailableToolIsHiddenWithReason() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(
                List.of(new FixedTool(Set.of("webSearch"), Availability.unavailable("BRAVE_API_KEY not set"))));
        assertThat(report.hidden()).containsExactly(new Hidden("webSearch", "BRAVE_API_KEY not set"));
    }

    @Test
    void checkExceptionIsFailSafeAndDoesNotStopOthers() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(List.of(
                new ThrowingTool(Set.of("flaky")),
                new FixedTool(Set.of("ok"), Availability.AVAILABLE)));
        assertThat(report.hidden()).hasSize(1);
        Hidden h = report.hidden().get(0);
        assertThat(h.toolName()).isEqualTo("flaky");
        assertThat(h.reason()).contains("availability check failed");
    }

    @Test
    void toolWithoutAvailabilityInterfaceDefaultsToAvailable() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(List.of(new PlainTool()));
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void nullResultTreatedAsUnavailable() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(
                List.of(new FixedTool(Set.of("webSearch"), null)));
        assertThat(report.hidden()).hasSize(1);
        assertThat(report.hidden().get(0).reason()).contains("no result");
    }

    @Test
    void applyToRemovesUnavailableToolFromSchemaButKeepsAvailable() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new SampleTools()).apply();
        assertThat(toolkit.getToolNames()).contains("gatedTool", "alwaysTool");

        FixedTool gated = new FixedTool(Set.of("gatedTool"), Availability.unavailable("MISSING_KEY not set"));
        ToolAvailabilityReport report = ToolAvailabilityGate.applyTo(toolkit, List.of(gated));

        assertThat(toolkit.getToolNames()).doesNotContain("gatedTool");
        assertThat(toolkit.getToolNames()).contains("alwaysTool");
        assertThat(report.hidden()).containsExactly(new Hidden("gatedTool", "MISSING_KEY not set"));
        // Reason names the missing prerequisite only — never a credential value.
        assertThat(report.hidden().get(0).reason()).doesNotContain("=");
    }

    @Test
    void applyToOnAvailableToolLeavesSchemaIntact() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new SampleTools()).apply();

        FixedTool ok = new FixedTool(Set.of("gatedTool"), Availability.AVAILABLE);
        ToolAvailabilityReport report = ToolAvailabilityGate.applyTo(toolkit, List.of(ok));

        assertThat(report.isEmpty()).isTrue();
        assertThat(toolkit.getToolNames()).contains("gatedTool", "alwaysTool");
    }
}
