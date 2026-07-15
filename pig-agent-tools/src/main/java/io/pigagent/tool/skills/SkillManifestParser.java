package io.pigagent.tool.skills;

/**
 * Strategy for turning raw {@code SKILL.md} text into a {@link SkillManifest} (metadata + body with
 * front-matter stripped). A pure function — no IO, no side effects — so it is trivially unit-testable
 * and swappable (a different front-matter dialect can drop in without touching callers). Mirrors the
 * pure-guard style of {@code SsrfGuard}.
 *
 * <p>Implementations MUST be tolerant and never throw: missing front-matter derives the metadata from
 * the name / first body line; malformed front-matter (no closing fence) degrades to "whole text is the
 * body".
 */
public interface SkillManifestParser {

    /**
     * @param text         the full (or head-prefix) {@code SKILL.md} text; {@code null} → treated as empty
     * @param fallbackName the skill name to use when front-matter omits {@code name} (e.g. the dir name)
     * @return parsed metadata + front-matter-stripped body; never {@code null}
     */
    SkillManifest parse(String text, String fallbackName);

    /** The default front-matter parser (YAML-ish, tolerant). */
    static SkillManifestParser defaults() {
        return FrontMatterManifestParser.INSTANCE;
    }
}
