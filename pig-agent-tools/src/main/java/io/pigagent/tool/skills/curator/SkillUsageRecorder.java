package io.pigagent.tool.skills.curator;

/**
 * Seam for recording skill-usage signals from pig's own read path ("engine, not mouth"): because pig
 * keeps {@code disableDynamicSkills()} and never installs the native {@code SkillUsageMiddleware} /
 * {@code SkillLoadTool} that would feed the native {@code SkillUsageStore} in the reasoning loop, pig
 * must <b>self-feed</b> usage from {@code SkillsTool.loadSkill}.
 *
 * <p>Mockable Strategy seam (mirrors {@code CompressionService.Summarizer} /
 * {@code ProfileDistiller} / {@code Embedder}): the default {@link #noop()} does nothing, so the
 * skill-curator feature is off by default with zero behavior change. Implementations MUST be
 * fault-tolerant — a usage-recording failure must never affect the {@code loadSkill} result.
 */
public interface SkillUsageRecorder {

    /** Record one usage hit for {@code skillName} (a {@code loadSkill} that resolved a skill). */
    void record(String skillName);

    /**
     * Mark {@code skillName} as agent-created so the native usage store will track it (native
     * {@code bumpUse} is provenance-gated — it only counts skills with a non-null {@code createdBy}).
     * Called when a drafted skill is promoted; the default is a no-op.
     */
    default void markCreated(String skillName) {
        // no-op by default
    }

    /** A recorder that does nothing — the default, so usage recording is off unless wired on. */
    static SkillUsageRecorder noop() {
        return skillName -> {
            // no-op
        };
    }
}
