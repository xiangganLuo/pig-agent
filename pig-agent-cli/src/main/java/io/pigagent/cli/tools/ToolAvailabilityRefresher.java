package io.pigagent.cli.tools;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.availability.ToolAvailability;
import io.pigagent.tool.availability.ToolAvailabilityGate;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.contract.ToolContractGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime availability hot re-evaluation ({@code tools-observability}, T3): the operator-triggered
 * complement to the bootstrap-once {@link ToolAvailabilityGate}. It re-runs each gated tool's
 * {@link ToolAvailability} check against the current environment and reconciles the shared toolkit —
 * re-registering (and re-guarding) a tool whose prerequisite is now satisfied, removing one whose
 * prerequisite has disappeared. When the visible set changes it fires the shared rebuild {@code
 * Runnable} (the same one the MCP {@code toolsChangedCallback} uses) so the rebuilt agents' native
 * permission context re-snapshots the new tool set.
 *
 * <p><b>Triggered only</b> — there is no polling/watching; the operator calls {@code /tools refresh}.
 * The current {@link ToolAvailabilityReport} is published into a shared {@link AtomicReference} so the
 * tool inventory reflects the latest state. Availability reasons are prerequisite names only (never a
 * credential value), per the availability-report convention.
 */
public final class ToolAvailabilityRefresher {

    private static final Logger log = LoggerFactory.getLogger(ToolAvailabilityRefresher.class);

    private final Toolkit toolkit;
    private final List<Object> gatedTools;
    private final AtomicReference<ToolAvailabilityReport> reportRef;
    private final Runnable rebuild;

    /**
     * @param toolkit    the shared base toolkit the interactive/channel agents are rebuilt from
     * @param gatedTools the tool instances originally passed through the availability gate (retained so
     *                   a re-surfaced tool can be re-registered); only {@link ToolAvailability} ones act
     * @param reportRef  the shared, mutable current availability report (read by the tool inventory)
     * @param rebuild    fires when the visible set changes (rebuild agents); may be {@code null} (tests)
     */
    public ToolAvailabilityRefresher(Toolkit toolkit, List<Object> gatedTools,
                                     AtomicReference<ToolAvailabilityReport> reportRef, Runnable rebuild) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.gatedTools = gatedTools == null ? List.of() : List.copyOf(gatedTools);
        this.reportRef = Objects.requireNonNull(reportRef, "reportRef");
        this.rebuild = rebuild;
    }

    /** The outcome of one re-evaluation: which tools became visible / hidden, and whether anything changed. */
    public record RefreshOutcome(List<String> surfaced, List<String> hidden) {
        public RefreshOutcome {
            surfaced = surfaced == null ? List.of() : List.copyOf(surfaced);
            hidden = hidden == null ? List.of() : List.copyOf(hidden);
        }

        public boolean changed() {
            return !surfaced.isEmpty() || !hidden.isEmpty();
        }
    }

    /**
     * Re-evaluate availability now. Re-surfaces tools whose prerequisites are freshly satisfied and
     * hides tools whose prerequisites have vanished, then (if anything changed) rebuilds the agents.
     * Never throws — a per-tool failure is logged and skipped (fail-safe, like the gate).
     */
    public synchronized RefreshOutcome refresh() {
        ToolAvailabilityReport report = ToolAvailabilityGate.evaluate(gatedTools);
        Set<String> hiddenNames = new java.util.HashSet<>();
        report.hidden().forEach(h -> hiddenNames.add(h.toolName()));

        Set<String> present = toolkit.getToolNames();
        List<String> surfaced = new ArrayList<>();
        List<String> hidden = new ArrayList<>();

        for (Object tool : gatedTools) {
            if (!(tool instanceof ToolAvailability ta)) {
                continue; // tools with no availability check are always available — nothing to reconcile
            }
            Set<String> governed = ta.availabilityToolNames();
            if (governed == null) {
                continue;
            }
            boolean available = governed.stream().noneMatch(hiddenNames::contains);
            for (String name : governed) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                boolean inKit = present.contains(name);
                if (available && !inKit) {
                    if (reRegister(tool, name)) {
                        surfaced.add(name);
                    }
                } else if (!available && inKit) {
                    safeRemove(name);
                    hidden.add(name);
                }
            }
        }

        // Re-guard any freshly re-registered tools (install is idempotent — already-guarded tools skip).
        if (!surfaced.isEmpty()) {
            ToolContractGuard.install(toolkit);
        }
        reportRef.set(report);
        RefreshOutcome outcome = new RefreshOutcome(surfaced, hidden);
        if (outcome.changed() && rebuild != null) {
            try {
                rebuild.run();
            } catch (RuntimeException e) {
                log.warn("Agent rebuild after availability refresh failed: {}", e.toString());
            }
        }
        return outcome;
    }

    /** Re-register a tool instance so its (now-available) @Tool methods re-enter the toolkit. */
    private boolean reRegister(Object tool, String name) {
        try {
            toolkit.registration().tool(tool).apply();
            log.info("Tool '{}' re-surfaced by availability refresh", name);
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to re-surface tool '{}': {}", name, e.toString());
            return false;
        }
    }

    private void safeRemove(String name) {
        try {
            toolkit.removeTool(name);
            log.info("Tool '{}' hidden by availability refresh (prerequisite gone)", name);
        } catch (RuntimeException e) {
            log.warn("Failed to hide tool '{}': {}", name, e.toString());
        }
    }
}
