package io.pigagent.tool.skills;

import java.util.List;

/**
 * Immutable metadata snapshot for a {@link Skill}, surfaced cheaply at listing time (progressive
 * loading). Parsed from an optional {@code SKILL.md} YAML front-matter by a {@link SkillManifestParser},
 * or derived from the skill name / first body line when no front-matter is present.
 *
 * <p>A pure value type (record): {@code keywords} is defensively copied to an unmodifiable list and
 * {@code null} fields are normalized to empty, so a {@code SkillMetadata} is always safe to read.
 *
 * @param name        stable skill name (never blank)
 * @param description one-line summary shown by {@code listSkills} (empty when unknown)
 * @param keywords    trigger words / "when to use" hints (never {@code null}; may be empty)
 * @param version     optional version string (empty when unknown)
 */
public record SkillMetadata(String name, String description, List<String> keywords, String version) {

    public SkillMetadata {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        version = version == null ? "" : version.trim();
    }

    /** Fallback metadata for a skill with no parsable front-matter: just the name, everything else empty. */
    public static SkillMetadata ofName(String name) {
        return new SkillMetadata(name, "", List.of(), "");
    }

    /** True when a human-readable one-line description is available (for {@code listSkills} formatting). */
    public boolean hasDescription() {
        return !description.isBlank();
    }
}
