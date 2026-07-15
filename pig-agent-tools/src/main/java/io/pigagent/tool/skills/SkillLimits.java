package io.pigagent.tool.skills;

/**
 * Immutable bounds on a composite skill's on-disk footprint and how much of it {@code loadSkill}
 * surfaces — a value object that keeps every skill-size cap in one place (mirroring the
 * defaults-are-safe philosophy of {@code SandboxPolicy}). {@link #defaults()} is safe with no
 * configuration; the caps exist to stop a pathologically large / zip-bomb-ish skill directory from
 * blowing up listing cost or the model context.
 *
 * @param maxSkillBytes          reject a {@code SKILL.md} larger than this at discovery (skill skipped)
 * @param maxSupportingFileBytes only inline a supporting text file up to this size (else list-only)
 * @param maxSupportingFiles     surface at most this many supporting files per skill
 * @param maxInlineBytes         inline at most this many total bytes across a skill's supporting files
 */
public record SkillLimits(long maxSkillBytes, long maxSupportingFileBytes,
                          int maxSupportingFiles, long maxInlineBytes) {

    private static final long DEFAULT_MAX_SKILL_BYTES = 256L * 1024;      // 256 KiB
    private static final long DEFAULT_MAX_SUPPORTING_FILE_BYTES = 32L * 1024; // 32 KiB
    private static final int DEFAULT_MAX_SUPPORTING_FILES = 64;
    private static final long DEFAULT_MAX_INLINE_BYTES = 128L * 1024;     // 128 KiB

    public SkillLimits {
        if (maxSkillBytes <= 0) {
            maxSkillBytes = DEFAULT_MAX_SKILL_BYTES;
        }
        if (maxSupportingFileBytes <= 0) {
            maxSupportingFileBytes = DEFAULT_MAX_SUPPORTING_FILE_BYTES;
        }
        if (maxSupportingFiles <= 0) {
            maxSupportingFiles = DEFAULT_MAX_SUPPORTING_FILES;
        }
        if (maxInlineBytes <= 0) {
            maxInlineBytes = DEFAULT_MAX_INLINE_BYTES;
        }
    }

    /** Safe defaults requiring no configuration. */
    public static SkillLimits defaults() {
        return new SkillLimits(DEFAULT_MAX_SKILL_BYTES, DEFAULT_MAX_SUPPORTING_FILE_BYTES,
                DEFAULT_MAX_SUPPORTING_FILES, DEFAULT_MAX_INLINE_BYTES);
    }
}
