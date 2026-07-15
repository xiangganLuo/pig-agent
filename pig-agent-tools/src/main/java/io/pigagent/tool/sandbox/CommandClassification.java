package io.pigagent.tool.sandbox;

/**
 * The three-tier verdict of {@link CommandGuard#classify(String)} (capability {@code exec-sandbox}):
 * a command is either {@code BLOCK} (catastrophic — refuse, don't spawn), {@code WARN} (medium-risk but
 * legitimate — run, but flag), or {@code PASS} (clean — run silently). Immutable value; the {@code reason}
 * is a category label only (never the original command), so it is safe to surface to the model/user.
 *
 * <p>Ordering is <strong>most-severe-wins</strong>: a command matching both a warn and a block pattern
 * is {@code BLOCK}. Warn is purely additive over the pre-existing block/pass contract — see
 * {@link CommandGuard#checkDenied(String)}, which still reports {@code BLOCK} only.
 *
 * @param tier   the severity tier
 * @param reason the category reason ({@code null} for {@link Tier#PASS})
 */
public record CommandClassification(Tier tier, String reason) {

    /** Severity tiers, ordered least→most severe: {@code PASS < WARN < BLOCK}. */
    public enum Tier {
        PASS,
        WARN,
        BLOCK
    }

    /** The clean, no-reason pass verdict. */
    public static final CommandClassification PASS = new CommandClassification(Tier.PASS, null);

    /** A warn verdict with a category reason (command runs, result gets a ⚠️ note). */
    public static CommandClassification warn(String reason) {
        return new CommandClassification(Tier.WARN, reason);
    }

    /** A block verdict with a category reason (command is refused, never spawned). */
    public static CommandClassification block(String reason) {
        return new CommandClassification(Tier.BLOCK, reason);
    }

    public boolean isBlocked() {
        return tier == Tier.BLOCK;
    }

    public boolean isWarn() {
        return tier == Tier.WARN;
    }

    public boolean isPass() {
        return tier == Tier.PASS;
    }
}
