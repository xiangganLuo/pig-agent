package io.pigagent.tool.skills;

import io.pigagent.core.search.SearchDocument;

import java.util.List;

/**
 * A skill projected onto the shared retrieval contract {@link SearchDocument} — capability
 * {@code skill-matching} (Skills S2). Mirrors the tool line's {@code ToolDocument} and the memory
 * line's {@code MemoryDocument}: it lets {@code skill_search} rank via the same kernel ranker
 * ({@code io.pigagent.core.search}: {@code Bm25Index} + {@code HybridRanker} + CJK {@code Tokenizer})
 * as {@code memory_search}/{@code tool_search}, so the three retrieval lines never drift in scoring.
 * Only depends on {@code io.pigagent.core.search}, never on the memory domain.
 *
 * <p><b>Progressive loading.</b> The scored {@code text} is built ONLY from the cheap
 * {@link Skill#metadata()} (name + description + keywords) — it MUST NOT read the skill body
 * ({@link Skill#content()}), so ranking stays token-cheap, exactly like {@code listSkills}.
 *
 * @param id   the skill name (how ranked results are keyed back and how {@code loadSkill} resolves)
 * @param text the scored body: skill name + description + keywords (keywords carry trigger words the
 *             shared {@code Tokenizer} would not derive from the name alone, boosting recall)
 */
record SkillDocument(String id, String text) implements SearchDocument {

    /** Build from a skill using ONLY its cheap {@link Skill#metadata()} (never its body). */
    static SkillDocument of(Skill skill) {
        return of(skill.name(), skill.metadata());
    }

    /** Build directly from a name + metadata: {@code id}=name, {@code text}=name + description + keywords. */
    static SkillDocument of(String name, SkillMetadata meta) {
        return new SkillDocument(name, buildText(name, meta));
    }

    private static String buildText(String name, SkillMetadata meta) {
        StringBuilder sb = new StringBuilder();
        if (name != null) {
            sb.append(name);
        }
        if (meta != null) {
            if (meta.description() != null && !meta.description().isBlank()) {
                sb.append(' ').append(meta.description());
            }
            List<String> keywords = meta.keywords();
            if (keywords != null) {
                for (String k : keywords) {
                    if (k != null && !k.isBlank()) {
                        sb.append(' ').append(k);
                    }
                }
            }
        }
        return sb.toString();
    }
}
