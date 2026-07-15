package io.pigagent.core.compression;

/**
 * Immutable three-tier budget ratios for {@link ContextBudget}: how the context window is split
 * across the <em>pinned</em> (system + must-keep facts), <em>recent</em> (verbatim window), and
 * <em>summarized</em> (older, compressible) tiers.
 *
 * <p>The canonical constructor is fault-tolerant and self-normalizing: negative components are
 * clamped to zero, and the three components are divided by their sum so they always add up to 1.0.
 * A non-positive sum falls back to the {@link #defaults() default} split. This keeps allocation
 * math (in {@link ContextBudget}) deterministic regardless of how the ratios were configured.
 */
public record BudgetRatios(double pinned, double recent, double summarized) {

    /** Default split — conservative: a small pinned floor, a recent window, half for summary. */
    public static final double DEFAULT_PINNED = 0.2;
    public static final double DEFAULT_RECENT = 0.3;
    public static final double DEFAULT_SUMMARIZED = 0.5;

    public BudgetRatios {
        double p = pinned > 0 ? pinned : 0.0;
        double r = recent > 0 ? recent : 0.0;
        double s = summarized > 0 ? summarized : 0.0;
        double sum = p + r + s;
        if (sum <= 0.0) {
            p = DEFAULT_PINNED;
            r = DEFAULT_RECENT;
            s = DEFAULT_SUMMARIZED;
            sum = 1.0;
        }
        pinned = p / sum;
        recent = r / sum;
        summarized = s / sum;
    }

    /** The conservative default ratios (0.2 / 0.3 / 0.5). */
    public static BudgetRatios defaults() {
        return new BudgetRatios(DEFAULT_PINNED, DEFAULT_RECENT, DEFAULT_SUMMARIZED);
    }
}
