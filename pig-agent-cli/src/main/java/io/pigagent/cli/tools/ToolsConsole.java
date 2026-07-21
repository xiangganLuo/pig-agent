package io.pigagent.cli.tools;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.kernel.ToolActionResult;
import io.pigagent.core.agent.kernel.ToolAdmin;
import io.pigagent.core.agent.kernel.ToolGroupView;
import io.pigagent.core.agent.kernel.ToolInventoryEntry;
import io.pigagent.core.metrics.ToolMetricsRegistry;
import io.pigagent.core.metrics.ToolMetricsRegistry.ToolMetrics;
import io.pigagent.core.tool.RevealTargets;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.contract.CredentialSanitizer;
import io.pigagent.tool.deferred.DeferralPlan;
import io.pigagent.tool.deferred.DeferredToolGate;
import io.pigagent.tool.deferred.DeferredToolRegistry;
import io.pigagent.tool.deferred.ToolInfo;
import io.pigagent.tool.permission.ToolRiskClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The single home of the {@code /tools} operator surface ({@code tools-observability}, T3): builds the
 * read-only tool inventory (name / risk / availability / deferral status / metrics) consumed through
 * the {@link io.pigagent.core.agent.kernel.AgentKernel} façade, and implements the runtime tool
 * management ({@link ToolAdmin}: groups / enable / disable / refresh). Kept in the wiring layer because
 * it composes the tools-module collaborators (risk classifier, availability report, deferred registry,
 * metrics registry) the kernel package cannot import.
 *
 * <p>Every message it emits is credential-sanitized and availability reasons name only the missing
 * prerequisite (never a secret) — aligned with the availability-report + credential-hardening
 * conventions. {@code enable}/{@code disable} mutate the shared base toolkit's deferred-group state and
 * fire the shared rebuild so the running agents reflect the change.
 */
public final class ToolsConsole implements ToolAdmin {

    private static final Logger log = LoggerFactory.getLogger(ToolsConsole.class);

    private final Toolkit toolkit;
    private final Supplier<Map<String, String>> riskOverrides;
    private final java.util.concurrent.atomic.AtomicReference<ToolAvailabilityReport> availabilityRef;
    private final DeferredToolRegistry deferredRegistry;
    private final RevealTargets revealTargets;
    private final ToolMetricsRegistry metrics;
    private final Supplier<Set<String>> mcpGroups;
    private final ToolAvailabilityRefresher refresher;
    private final Runnable rebuild;

    /** Groups this console created at runtime via {@link #disable} (so {@link #groups} can list them). */
    private final Set<String> runtimeGroups = new LinkedHashSet<>();

    public ToolsConsole(Toolkit toolkit, Supplier<Map<String, String>> riskOverrides,
                        java.util.concurrent.atomic.AtomicReference<ToolAvailabilityReport> availabilityRef,
                        DeferredToolRegistry deferredRegistry, RevealTargets revealTargets,
                        ToolMetricsRegistry metrics, Supplier<Set<String>> mcpGroups,
                        ToolAvailabilityRefresher refresher, Runnable rebuild) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.riskOverrides = riskOverrides == null ? Map::of : riskOverrides;
        this.availabilityRef = Objects.requireNonNull(availabilityRef, "availabilityRef");
        this.deferredRegistry = deferredRegistry == null ? new DeferredToolRegistry() : deferredRegistry;
        this.revealTargets = revealTargets;
        this.metrics = metrics == null ? new ToolMetricsRegistry() : metrics;
        this.mcpGroups = mcpGroups == null ? Set::of : mcpGroups;
        this.refresher = refresher;
        this.rebuild = rebuild;
    }

    // ==================== inventory (kernel.listTools provider) ====================

    /** The full tool inventory: registered tools (active + deferred) plus availability-hidden ones. */
    public List<ToolInventoryEntry> list() {
        Map<String, String> overrides = riskOverrides.get();
        ToolAvailabilityReport report = availabilityRef.get();
        Set<String> hiddenSeen = new LinkedHashSet<>();
        List<ToolInventoryEntry> out = new ArrayList<>();

        for (String name : new TreeSet<>(toolkit.getToolNames())) {
            out.add(entry(name, overrides, true, ""));
        }
        if (report != null) {
            for (ToolAvailabilityReport.Hidden h : report.hidden()) {
                if (hiddenSeen.add(h.toolName()) && !toolkit.getToolNames().contains(h.toolName())) {
                    out.add(entry(h.toolName(), overrides, false, safe(h.reason())));
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private ToolInventoryEntry entry(String name, Map<String, String> overrides,
                                     boolean available, String reason) {
        String risk = ToolRiskClassifier.classify(name, overrides).name();
        ToolMetrics m = metrics.get(name).orElse(new ToolMetrics(name, 0, 0, 0));
        return new ToolInventoryEntry(name, risk, available, reason,
                deferralStatus(name), m.calls(), m.avgLatencyMillis(), m.errorRate());
    }

    private String deferralStatus(String name) {
        if (deferredRegistry.deferredNames().contains(name)) {
            return ToolInventoryEntry.DEFERRAL_DEFERRED;
        }
        if (deferredRegistry.revealedNames().contains(name)) {
            return ToolInventoryEntry.DEFERRAL_REVEALED;
        }
        return ToolInventoryEntry.DEFERRAL_NONE;
    }

    // ==================== ToolAdmin (groups / enable / disable / refresh) ====================

    @Override
    public List<ToolGroupView> groups() {
        Set<String> names = new LinkedHashSet<>();
        deferredRegistry.all().forEach(dt -> names.add(dt.groupName()));
        names.addAll(mcpGroups.get());
        names.addAll(runtimeGroups);
        List<ToolGroupView> out = new ArrayList<>();
        for (String g : names) {
            if (g == null || g.isBlank()) {
                continue;
            }
            ToolGroup group = toolkit.getToolGroup(g);
            if (group != null) {
                out.add(new ToolGroupView(g, group.isActive(), new ArrayList<>(new TreeSet<>(group.getTools()))));
            }
        }
        return out;
    }

    @Override
    public synchronized ToolActionResult enable(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolActionResult.noChange("No tool name given.");
        }
        String hiddenReason = availabilityReason(toolName);
        if (hiddenReason != null) {
            return ToolActionResult.noChange("'" + toolName + "' is unavailable: " + hiddenReason
                    + ". Set the prerequisite, then run /tools refresh.");
        }
        if (ToolInventoryEntry.DEFERRAL_DEFERRED.equals(deferralStatus(toolName))) {
            boolean revealed = DeferredToolGate.reveal(revealTargets, deferredRegistry).reveal(toolName);
            if (!revealed) {
                return ToolActionResult.noChange("'" + toolName + "' could not be revealed.");
            }
            fireRebuild();
            return ToolActionResult.changed("Enabled '" + toolName + "' (revealed; now visible to the model).");
        }
        if (toolkit.getToolNames().contains(toolName)) {
            return ToolActionResult.noChange("'" + toolName + "' is already enabled.");
        }
        return ToolActionResult.noChange("Unknown tool: '" + toolName + "'.");
    }

    @Override
    public synchronized ToolActionResult disable(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolActionResult.noChange("No tool name given.");
        }
        if (!toolkit.getToolNames().contains(toolName)) {
            String reason = availabilityReason(toolName);
            if (reason != null) {
                return ToolActionResult.noChange("'" + toolName + "' is already hidden (unavailable: " + reason + ").");
            }
            return ToolActionResult.noChange("Unknown tool: '" + toolName + "'.");
        }
        if (ToolInventoryEntry.DEFERRAL_DEFERRED.equals(deferralStatus(toolName))) {
            return ToolActionResult.noChange("'" + toolName + "' is already disabled (deferred).");
        }
        if (isMcpTool(toolName)) {
            return ToolActionResult.noChange("'" + toolName + "' is an MCP tool; use /mcp disable <server> to hide it.");
        }
        String group = DeferredToolGate.DEFER_GROUP_PREFIX + toolName;
        try {
            DeferredToolGate.applyTo(toolkit, new DeferralPlan(Set.of(toolName)),
                    List.of(ToolInfo.of(toolName, descriptionOf(toolName), null)), deferredRegistry);
            runtimeGroups.add(group);
        } catch (RuntimeException e) {
            log.warn("Failed to disable tool '{}': {}", toolName, e.toString());
            return ToolActionResult.noChange("Could not disable '" + toolName + "'.");
        }
        fireRebuild();
        return ToolActionResult.changed("Disabled '" + toolName + "' (deferred; hidden from the model).");
    }

    @Override
    public ToolActionResult refresh() {
        if (refresher == null) {
            return ToolActionResult.noChange("Availability refresh is not available.");
        }
        ToolAvailabilityRefresher.RefreshOutcome outcome = refresher.refresh();
        if (!outcome.changed()) {
            return ToolActionResult.noChange("Re-evaluated availability — no change.");
        }
        StringBuilder sb = new StringBuilder("Availability re-evaluated:");
        if (!outcome.surfaced().isEmpty()) {
            sb.append(" now available ").append(outcome.surfaced());
        }
        if (!outcome.hidden().isEmpty()) {
            sb.append(" now hidden ").append(outcome.hidden());
        }
        return ToolActionResult.changed(safe(sb.toString()));
    }

    // ==================== helpers ====================

    /** The availability-hidden reason for a tool, or null when it is not hidden by availability. */
    private String availabilityReason(String name) {
        ToolAvailabilityReport report = availabilityRef.get();
        if (report == null) {
            return null;
        }
        for (ToolAvailabilityReport.Hidden h : report.hidden()) {
            if (h.toolName().equals(name)) {
                return safe(h.reason());
            }
        }
        return null;
    }

    private boolean isMcpTool(String name) {
        for (String g : mcpGroups.get()) {
            ToolGroup group = toolkit.getToolGroup(g);
            if (group != null && group.getTools().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private String descriptionOf(String name) {
        for (ToolSchema schema : toolkit.getToolSchemas()) {
            if (name.equals(schema.getName())) {
                return schema.getDescription();
            }
        }
        return "";
    }

    private void fireRebuild() {
        if (rebuild != null) {
            try {
                rebuild.run();
            } catch (RuntimeException e) {
                log.warn("Agent rebuild after /tools change failed: {}", e.toString());
            }
        }
    }

    private static String safe(String s) {
        return CredentialSanitizer.sanitize(s);
    }
}
