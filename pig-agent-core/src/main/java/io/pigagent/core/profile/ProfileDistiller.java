package io.pigagent.core.profile;

/**
 * The (mockable) model seam for background profile distillation — capability {@code user-profile}.
 * Given the current {@code USER.md} body and the consolidated long-term memory ({@code MEMORY.md}),
 * produce a fresh, deduped, curated profile body (identity / durable preferences / working style).
 *
 * <p>The live implementation ({@link ModelProfileDistiller}) runs a throwaway agent on a cheap model
 * (reusing the {@code pa-memory-native} cheap-model pattern); tests inject a deterministic fake, so the
 * throttle / fault-tolerance / write path of {@link ProfileConsolidationService} is exercised offline
 * without any live model. Returning {@code null}/blank aborts the consolidation (profile left as-is).
 */
@FunctionalInterface
public interface ProfileDistiller {

    /**
     * @param currentProfile the current {@code USER.md} body (may be empty on first run — seed identity
     *        from {@code memory} then).
     * @param memory the consolidated long-term memory ({@code MEMORY.md}) to distill durable
     *        identity/preferences from.
     * @return the new curated profile body, or {@code null}/blank to abort (keep the existing profile).
     */
    String distill(String currentProfile, String memory);
}
