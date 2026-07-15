package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;

import java.util.List;

/**
 * The single source of truth for the built-in skill set: a curated list of skill names, each backed
 * by a {@code skills/<name>/SKILL.md} classpath resource. Used for programmatic introspection and
 * tests; the runtime discovery path is {@link BuiltinSkillProvider} declared in
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider} and driven by
 * {@code ClasspathSkillSource}.
 *
 * <p>Mirrors {@code io.pigagent.plugin.builtin.PluginCatalog}. {@link #all()} and the classpath
 * resource directory are two views of the same set; a consistency test asserts they match, so adding
 * a skill name without shipping its {@code SKILL.md} (or vice-versa) is caught as drift.
 *
 * <p>The set is a starter kit of general engineering methods for a coding agent — each an actionable,
 * agent-facing guide (title + when-to-use + a concrete step-by-step method / checklist).
 */
public final class SkillCatalog {

    /** Resource directory prefix for a skill's {@code SKILL.md}. */
    static final String RESOURCE_PREFIX = "skills/";
    static final String SKILL_FILE = "SKILL.md";

    /** Curated built-in skill names, in a stable order. */
    public static final List<String> SKILL_NAMES = List.of(
            "code-review",
            "systematic-debugging",
            "tdd",
            "refactoring",
            "git-commit",
            "security-review",
            "planning");

    private SkillCatalog() {
    }

    /** Every built-in skill as a lazily-loaded {@link Skill}, in {@link #SKILL_NAMES} order. */
    public static List<Skill> all() {
        ClassLoader loader = SkillCatalog.class.getClassLoader();
        return SKILL_NAMES.stream()
                .map(name -> (Skill) new ClasspathSkill(name, resourcePath(name), loader))
                .toList();
    }

    /** The classpath resource path of a skill's {@code SKILL.md}. */
    static String resourcePath(String name) {
        return RESOURCE_PREFIX + name + "/" + SKILL_FILE;
    }
}
