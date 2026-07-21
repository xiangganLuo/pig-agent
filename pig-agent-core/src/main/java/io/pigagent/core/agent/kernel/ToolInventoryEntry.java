package io.pigagent.core.agent.kernel;

/**
 * A read-only inventory row for one tool, exposed through the {@link AgentKernel} façade
 * ({@code tools-observability}, T3) so {@code /tools} and future frontends consume the tool "process
 * table" without depending on internal toolkit / risk-classifier / availability / deferred / metrics
 * types.
 *
 * <p>Deliberately built from plain values (a {@code String} risk name rather than the tools-module
 * {@code ToolRisk} enum) so the record can live in the core kernel package while the classification
 * itself stays the tools module's central table. The entry MUST NOT carry any credential value — the
 * {@code availabilityReason} names only the missing prerequisite (aligned with the availability report
 * convention).
 *
 * @param name               the tool name (as the model would call it)
 * @param risk               the risk classification name (e.g. {@code READ_ONLY}); never null
 * @param available          whether the tool's availability preconditions are currently met
 * @param availabilityReason when unavailable, the missing-prerequisite reason (never a credential);
 *                           empty when available
 * @param deferralStatus     one of {@code "none"} / {@code "deferred"} / {@code "revealed"}
 * @param calls              observed dispatch count (0 when never called)
 * @param avgLatencyMillis   mean latency per call in ms (0 when never called)
 * @param errorRate          fraction of calls that errored, in {@code [0.0, 1.0]}
 */
public record ToolInventoryEntry(
        String name,
        String risk,
        boolean available,
        String availabilityReason,
        String deferralStatus,
        long calls,
        long avgLatencyMillis,
        double errorRate) {

    /** Deferral status: the tool is active (neither deferred nor previously revealed). */
    public static final String DEFERRAL_NONE = "none";
    /** Deferral status: the tool is currently deferred (hidden from the initial schema). */
    public static final String DEFERRAL_DEFERRED = "deferred";
    /** Deferral status: the tool was deferred and has since been revealed (now active). */
    public static final String DEFERRAL_REVEALED = "revealed";

    public ToolInventoryEntry {
        risk = risk == null ? "" : risk;
        availabilityReason = availabilityReason == null ? "" : availabilityReason;
        deferralStatus = deferralStatus == null ? DEFERRAL_NONE : deferralStatus;
    }
}
