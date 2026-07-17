package io.pigagent.tool.skills.authoring;

/**
 * Strategy that decides whether a staged skill's {@code SKILL.md} is safe to promote. A pure function
 * (no IO, no side effects) so it is trivially unit-testable and swappable — mirroring the pure-guard
 * style of {@code SsrfGuard} / {@code SkillSecurity}. Implementations MUST be tolerant and never throw:
 * any internal failure degrades to a rejection, never an exception.
 */
public interface SkillContentScanner {

    /**
     * Scan a staged skill for safety before promotion.
     *
     * @param name    the skill (directory) name
     * @param skillMd the full {@code SKILL.md} text to be installed
     * @return a passing result, or a rejection with credential-redacted reasons; never {@code null}
     */
    SkillScanResult scan(String name, String skillMd);

    /** The default scanner (name/path + size + credential + structure checks). */
    static SkillContentScanner defaults() {
        return new DefaultSkillContentScanner();
    }
}
