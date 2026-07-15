package io.pigagent.tool.skills;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The agent's entry point for on-demand capability packs. A skill is a named {@code SKILL.md}; the
 * tool merges skills from several {@link SkillSource}s via a {@link SkillRegistry}:
 * <ul>
 *   <li>the <b>built-in</b> classpath set ({@link ClasspathSkillSource}, shipped by
 *       {@code pig-agent-skills-builtin}); and</li>
 *   <li>the <b>workspace</b> directory ({@link WorkspaceSkillSource}, {@code workspace/skills/}).</li>
 * </ul>
 * The workspace source has priority, so a user can override / shadow a built-in skill by dropping a
 * same-named {@code SKILL.md} into the workspace. With no built-in module on the classpath the
 * classpath source finds nothing and behaviour is exactly workspace-only (backward compatible).
 *
 * <p>The {@code @Tool} surface ({@link #listSkills()} / {@link #loadSkill(String)}) — names,
 * signatures and return semantics — is unchanged from the original single-directory tool.
 */
public final class SkillsTool {

    private final SkillRegistry registry;

    /** Primary constructor: compose any set of sources (used by tests and explicit wiring). */
    public SkillsTool(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * Backward-compatible convenience: compose the workspace directory with the built-in classpath
     * skills. The workspace source is listed first so it overrides same-named built-in skills. When
     * no built-in {@code SkillProvider} is on the classpath this degrades to workspace-only.
     */
    public SkillsTool(Path skillsDir) {
        this(new SkillRegistry(List.of(
                new WorkspaceSkillSource(skillsDir),
                new ClasspathSkillSource())));
    }

    @Tool(description = "List available skills from the skills directory")
    public String listSkills() {
        List<String> names = registry.listNames();
        if (names.isEmpty()) {
            return "No skills found.";
        }
        return names.stream().map(name -> "- " + name)
                .reduce((a, b) -> a + "\n" + b).orElse("No skills found.");
    }

    @Tool(description = "Load and read a skill's content by name")
    public String loadSkill(@ToolParam(name = "skill_name", description = "Skill name") String skillName) {
        Optional<Skill> skill = registry.find(skillName);
        if (skill.isEmpty()) {
            return "Skill not found: " + skillName;
        }
        try {
            return skill.get().content();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}
