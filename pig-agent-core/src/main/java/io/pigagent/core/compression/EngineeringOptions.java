package io.pigagent.core.compression;

/**
 * Immutable, fault-tolerant knobs for {@link ContextEngineer}. All values are validated/clamped in
 * the canonical constructor so a bad config can never crash compression.
 *
 * <p>{@link #defaults()} reproduces the prior "summarize-old, keep-recent" behavior on small
 * plain-text conversations (recursion never triggers, nothing is pulled verbatim, no protected
 * spans), so existing compression tests stay green. {@link #safe()} is the "less aggressive"
 * fallback profile used when the consistency check fails: verbatim protection and importance
 * retention are forced on and recursion is disabled (single-level summary), so critical content is
 * maximally preserved.
 */
public record EngineeringOptions(
        boolean recursiveSummary,
        int maxSummaryDepth,
        boolean importanceRetention,
        boolean verbatimProtection,
        boolean consistencyCheck,
        int keepRecent,
        BudgetRatios ratios) {

    public static final int DEFAULT_KEEP_RECENT = 6;
    public static final int DEFAULT_MAX_DEPTH = 3;

    public EngineeringOptions {
        maxSummaryDepth = Math.max(0, maxSummaryDepth);
        keepRecent = keepRecent < 1 ? DEFAULT_KEEP_RECENT : keepRecent;
        ratios = ratios == null ? BudgetRatios.defaults() : ratios;
    }

    /** Defaults that reproduce prior behavior on small plain conversations. */
    public static EngineeringOptions defaults() {
        return new EngineeringOptions(true, DEFAULT_MAX_DEPTH, true, true, true,
                DEFAULT_KEEP_RECENT, BudgetRatios.defaults());
    }

    /**
     * The safer, less-aggressive profile: force verbatim protection + importance retention on and
     * disable recursion (single-level summary). Keeps this instance's {@code keepRecent}/{@code
     * ratios}/{@code consistencyCheck} so the fallback candidate is re-checked the same way.
     */
    public EngineeringOptions safe() {
        return new EngineeringOptions(false, 0, true, true, consistencyCheck, keepRecent, ratios);
    }
}
