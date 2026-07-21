package io.pigagent.cli.tools;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.availability.ToolAvailabilityGate;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the runtime availability hot re-evaluation ({@code tools-observability}, T3): a now-satisfied
 * prerequisite re-surfaces a tool without a restart and fires the rebuild; a vanished prerequisite
 * hides it; the shared report is updated; reasons never contain a credential value.
 */
class ToolAvailabilityRefresherTest {

    @Test
    void prerequisiteSatisfied_reSurfacesToolAndRebuilds() {
        // Arrange: tool starts unavailable → gate removes it from the toolkit.
        AtomicBoolean available = new AtomicBoolean(false);
        ToggleAvailabilityTool tool = new ToggleAvailabilityTool(available);
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(tool).apply();
        List<Object> gated = List.of(tool);
        ToolAvailabilityReport initial = ToolAvailabilityGate.applyTo(toolkit, gated);
        assertThat(toolkit.getToolNames()).doesNotContain("webSearch");
        assertThat(initial.hidden()).extracting(ToolAvailabilityReport.Hidden::toolName).contains("webSearch");

        AtomicReference<ToolAvailabilityReport> ref = new AtomicReference<>(initial);
        AtomicInteger rebuilds = new AtomicInteger();
        ToolAvailabilityRefresher refresher =
                new ToolAvailabilityRefresher(toolkit, gated, ref, rebuilds::incrementAndGet);

        // Act: prerequisite now satisfied
        available.set(true);
        ToolAvailabilityRefresher.RefreshOutcome outcome = refresher.refresh();

        // Assert: re-surfaced, present in toolkit, rebuild fired, report cleared
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.surfaced()).contains("webSearch");
        assertThat(toolkit.getToolNames()).contains("webSearch");
        assertThat(rebuilds.get()).isEqualTo(1);
        assertThat(ref.get().hidden()).isEmpty();
    }

    @Test
    void prerequisiteVanished_hidesTool() {
        // Arrange: available → present in toolkit
        AtomicBoolean available = new AtomicBoolean(true);
        ToggleAvailabilityTool tool = new ToggleAvailabilityTool(available);
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(tool).apply();
        List<Object> gated = List.of(tool);
        AtomicReference<ToolAvailabilityReport> ref =
                new AtomicReference<>(ToolAvailabilityGate.applyTo(toolkit, gated));
        assertThat(toolkit.getToolNames()).contains("webSearch");
        AtomicInteger rebuilds = new AtomicInteger();
        ToolAvailabilityRefresher refresher =
                new ToolAvailabilityRefresher(toolkit, gated, ref, rebuilds::incrementAndGet);

        // Act: prerequisite gone
        available.set(false);
        ToolAvailabilityRefresher.RefreshOutcome outcome = refresher.refresh();

        // Assert
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.hidden()).contains("webSearch");
        assertThat(toolkit.getToolNames()).doesNotContain("webSearch");
        assertThat(rebuilds.get()).isEqualTo(1);
    }

    @Test
    void noChange_doesNotRebuild_andReasonHasNoCredential() {
        // Arrange: stays unavailable
        AtomicBoolean available = new AtomicBoolean(false);
        ToggleAvailabilityTool tool = new ToggleAvailabilityTool(available);
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(tool).apply();
        List<Object> gated = List.of(tool);
        AtomicReference<ToolAvailabilityReport> ref =
                new AtomicReference<>(ToolAvailabilityGate.applyTo(toolkit, gated));
        AtomicInteger rebuilds = new AtomicInteger();
        ToolAvailabilityRefresher refresher =
                new ToolAvailabilityRefresher(toolkit, gated, ref, rebuilds::incrementAndGet);

        // Act: refresh with no change
        ToolAvailabilityRefresher.RefreshOutcome outcome = refresher.refresh();

        // Assert: no change, no rebuild, reason names the prerequisite only
        assertThat(outcome.changed()).isFalse();
        assertThat(rebuilds.get()).isZero();
        assertThat(ref.get().hidden()).extracting(ToolAvailabilityReport.Hidden::reason)
                .allSatisfy(r -> assertThat(r).contains("SEARCH_KEY").doesNotContain("sk-"));
    }
}
