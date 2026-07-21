package io.pigagent.cli.tools;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.kernel.ToolActionResult;
import io.pigagent.core.agent.kernel.ToolGroupView;
import io.pigagent.core.agent.kernel.ToolInventoryEntry;
import io.pigagent.core.metrics.ToolMetricsRegistry;
import io.pigagent.core.tool.RevealTargets;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.deferred.DeferredToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code /tools} logic home ({@code tools-observability}, T3): the read-only inventory
 * (risk / availability / deferral / metrics), and runtime management (enable/disable/groups) on a real
 * Toolkit. Availability-hidden tools surface in the inventory + are not openable via enable.
 */
class ToolsConsoleTest {

    private static Set<String> schemaNames(Toolkit tk) {
        return tk.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private ToolsConsole console(Toolkit tk, ToolAvailabilityReport report,
                                 ToolMetricsRegistry metrics, AtomicInteger rebuilds) {
        RevealTargets targets = new RevealTargets();
        targets.register(tk);
        return new ToolsConsole(
                tk, Map::of, new AtomicReference<>(report),
                new DeferredToolRegistry(), targets, metrics,
                Set::of, null, rebuilds::incrementAndGet);
    }

    @Test
    void list_includesRiskAvailabilityDeferralAndMetrics() {
        // Arrange
        Toolkit tk = new Toolkit();
        tk.registration().tool(new ToolsSampleTools()).apply();
        ToolMetricsRegistry metrics = new ToolMetricsRegistry();
        metrics.recordCall("readFile", 20);
        ToolAvailabilityReport report = new ToolAvailabilityReport(
                List.of(new ToolAvailabilityReport.Hidden("webSearch", "SEARCH_KEY not set")));
        ToolsConsole console = console(tk, report, metrics, new AtomicInteger());

        // Act
        List<ToolInventoryEntry> inventory = console.list();

        // Assert: registered tools present + available; risk from the classifier; metrics reflected
        ToolInventoryEntry readFile = find(inventory, "readFile");
        assertThat(readFile.risk()).isEqualTo("READ_ONLY");
        assertThat(readFile.available()).isTrue();
        assertThat(readFile.calls()).isEqualTo(1);
        assertThat(readFile.avgLatencyMillis()).isEqualTo(20);
        assertThat(find(inventory, "writeFile").risk()).isEqualTo("WRITE");
        // the availability-hidden tool is present, unavailable, reason names the prerequisite only
        ToolInventoryEntry webSearch = find(inventory, "webSearch");
        assertThat(webSearch.available()).isFalse();
        assertThat(webSearch.availabilityReason()).contains("SEARCH_KEY").doesNotContain("sk-");
    }

    @Test
    void disable_defersTool_hidesFromSchema_andRebuilds() {
        // Arrange
        Toolkit tk = new Toolkit();
        tk.registration().tool(new ToolsSampleTools()).apply();
        AtomicInteger rebuilds = new AtomicInteger();
        ToolsConsole console = console(tk, ToolAvailabilityReport.empty(), new ToolMetricsRegistry(), rebuilds);

        // Act
        ToolActionResult r = console.disable("writeFile");

        // Assert
        assertThat(r.changed()).isTrue();
        assertThat(schemaNames(tk)).doesNotContain("writeFile").contains("readFile");
        assertThat(rebuilds.get()).isEqualTo(1);
        assertThat(find(console.list(), "writeFile").deferralStatus())
                .isEqualTo(ToolInventoryEntry.DEFERRAL_DEFERRED);
    }

    @Test
    void enable_revealsDeferredTool_backIntoSchema() {
        // Arrange: disable then enable
        Toolkit tk = new Toolkit();
        tk.registration().tool(new ToolsSampleTools()).apply();
        ToolsConsole console = console(tk, ToolAvailabilityReport.empty(), new ToolMetricsRegistry(), new AtomicInteger());
        console.disable("writeFile");
        assertThat(schemaNames(tk)).doesNotContain("writeFile");

        // Act
        ToolActionResult r = console.enable("writeFile");

        // Assert
        assertThat(r.changed()).isTrue();
        assertThat(schemaNames(tk)).contains("writeFile");
        assertThat(find(console.list(), "writeFile").deferralStatus())
                .isEqualTo(ToolInventoryEntry.DEFERRAL_REVEALED);
    }

    @Test
    void enable_onAvailabilityHiddenTool_returnsHintAndDoesNotOpen() {
        // Arrange: webSearch hidden by availability, not in the toolkit
        Toolkit tk = new Toolkit();
        tk.registration().tool(new ToolsSampleTools()).apply();
        ToolAvailabilityReport report = new ToolAvailabilityReport(
                List.of(new ToolAvailabilityReport.Hidden("webSearch", "SEARCH_KEY not set")));
        ToolsConsole console = console(tk, report, new ToolMetricsRegistry(), new AtomicInteger());

        // Act
        ToolActionResult r = console.enable("webSearch");

        // Assert: not changed, message hints the prerequisite + refresh, no credential
        assertThat(r.changed()).isFalse();
        assertThat(r.message()).contains("SEARCH_KEY").contains("refresh").doesNotContain("sk-");
        assertThat(schemaNames(tk)).doesNotContain("webSearch");
    }

    @Test
    void groups_listsDeferredGroupAfterDisable() {
        // Arrange
        Toolkit tk = new Toolkit();
        tk.registration().tool(new ToolsSampleTools()).apply();
        ToolsConsole console = console(tk, ToolAvailabilityReport.empty(), new ToolMetricsRegistry(), new AtomicInteger());
        console.disable("writeFile");

        // Act
        List<ToolGroupView> groups = console.groups();

        // Assert: the runtime deferred group is present, inactive, with writeFile as a member
        ToolGroupView g = groups.stream().filter(v -> v.name().equals("deferred__writeFile"))
                .findFirst().orElseThrow();
        assertThat(g.active()).isFalse();
        assertThat(g.tools()).contains("writeFile");
    }

    private static ToolInventoryEntry find(List<ToolInventoryEntry> inv, String name) {
        Optional<ToolInventoryEntry> e = inv.stream().filter(x -> x.name().equals(name)).findFirst();
        assertThat(e).as("tool %s in inventory", name).isPresent();
        return e.get();
    }
}
