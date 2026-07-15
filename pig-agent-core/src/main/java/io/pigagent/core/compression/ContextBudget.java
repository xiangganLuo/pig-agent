package io.pigagent.core.compression;

/**
 * Deterministic three-tier allocation of a token budget across the <em>pinned</em>,
 * <em>recent</em> (verbatim window), and <em>summarized</em> tiers.
 *
 * <p>Pure value object with no model dependency — fully unit-testable. Given a total budget and
 * {@link BudgetRatios}, {@link #allocate} floors the pinned and recent tiers and gives the
 * remainder (rounding included) to the summarized tier, guaranteeing the three tiers <em>always</em>
 * sum to exactly {@code totalTokens}.
 *
 * <p>The {@link #summarizedTokens} tier bounds recursive summarization (its budget is the recursion
 * trigger threshold); the {@link #pinnedTokens} tier caps how much older content may be kept
 * verbatim by importance retention.
 */
public record ContextBudget(int totalTokens, int pinnedTokens, int recentTokens, int summarizedTokens) {

    /**
     * Allocate {@code totalTokens} across the three tiers per {@code ratios} (null → defaults).
     * Negative totals are treated as zero. Deterministic: same inputs always yield the same split.
     */
    public static ContextBudget allocate(int totalTokens, BudgetRatios ratios) {
        int total = Math.max(0, totalTokens);
        BudgetRatios r = ratios == null ? BudgetRatios.defaults() : ratios;
        int pinned = (int) Math.floor(total * r.pinned());
        int recent = (int) Math.floor(total * r.recent());
        int summarized = total - pinned - recent; // remainder absorbs rounding → sum == total
        return new ContextBudget(total, pinned, recent, summarized);
    }
}
