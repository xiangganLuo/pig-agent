package io.pigagent.tool.skills.authoring;

import java.util.List;

/**
 * An immutable draft of a skill the agent distilled from a solved task/workflow — the input to the
 * autonomous-skills write path (staged, never installed directly). A value type (record):
 * {@code keywords} is defensively copied to an unmodifiable list and {@code null} fields normalize to
 * empty, so a draft is always safe to render.
 *
 * <p>{@link #toSkillMd()} renders the draft into a {@code SKILL.md} document with a {@code ---}-fenced
 * YAML front-matter ({@code name}/{@code description}/{@code keywords}/{@code version}) followed by the
 * body — the exact shape {@code FrontMatterManifestParser} parses back, so the round-trip
 * {@code parse(draft.toSkillMd())} recovers equivalent metadata.
 *
 * @param name        skill / directory name (validated by {@code SkillSecurity.isValidSkillName})
 * @param description one-line summary surfaced by {@code listSkills}
 * @param keywords    trigger words / when-to-use hints (never {@code null}; may be empty)
 * @param body        the skill body (the method guide the agent will follow on {@code loadSkill})
 */
public record SkillDraft(String name, String description, List<String> keywords, String body) {

    public SkillDraft {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        body = body == null ? "" : body;
    }

    /** Render this draft to a {@code SKILL.md} document (YAML front-matter + body). Never {@code null}. */
    public String toSkillMd() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escape(name)).append('\n');
        sb.append("description: ").append(escape(description)).append('\n');
        if (!keywords.isEmpty()) {
            sb.append("keywords: ").append(escape(String.join(", ", keywords))).append('\n');
        }
        sb.append("version: 1\n");
        sb.append("---\n\n");
        sb.append(body.stripTrailing()).append('\n');
        return sb.toString();
    }

    /** Quote a value that would otherwise break the tolerant front-matter parser (leading/edge chars). */
    private static String escape(String v) {
        if (v.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = v.contains(":") || v.contains("#") || v.contains("\"") || v.contains("'")
                || v.startsWith("-") || v.startsWith(" ") || v.endsWith(" ") || v.contains("\n");
        if (!needsQuote) {
            return v;
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
