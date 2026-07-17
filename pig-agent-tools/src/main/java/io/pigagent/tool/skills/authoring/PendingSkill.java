package io.pigagent.tool.skills.authoring;

/**
 * A lightweight view of a staged (pending) skill draft, surfaced by the human gate ({@code /skill
 * review}) so an operator can triage without reading the whole body. Immutable value type.
 *
 * @param name        the staged skill name
 * @param description the draft's one-line description (from its front-matter/heading; may be empty)
 * @param sizeBytes   the staged {@code SKILL.md} size in bytes
 * @param scanPassed  whether the content safety scan currently passes (would it promote cleanly)
 */
public record PendingSkill(String name, String description, long sizeBytes, boolean scanPassed) {

    public PendingSkill {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
    }
}
