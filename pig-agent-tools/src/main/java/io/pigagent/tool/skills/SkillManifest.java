package io.pigagent.tool.skills;

/**
 * The result of parsing a {@code SKILL.md}: its {@link SkillMetadata} plus the {@code body} with any
 * YAML front-matter block stripped. Returned by {@link SkillManifestParser}.
 *
 * @param metadata parsed (or derived) metadata
 * @param body     the skill content with front-matter removed (the whole text when no front-matter)
 */
public record SkillManifest(SkillMetadata metadata, String body) {

    public SkillManifest {
        if (metadata == null) {
            metadata = SkillMetadata.ofName("");
        }
        if (body == null) {
            body = "";
        }
    }
}
