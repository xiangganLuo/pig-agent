package io.pigagent.tool.skills;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.skills.curator.SkillUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The agent's entry point for on-demand capability packs. A skill is a composite {@code SKILL.md}
 * bundle; the tool merges skills from several {@link SkillSource}s via a {@link SkillRegistry}:
 * <ul>
 *   <li>the <b>built-in</b> classpath set ({@link ClasspathSkillSource}, shipped by
 *       {@code pig-agent-skills-builtin}); and</li>
 *   <li>the <b>workspace</b> directory ({@link WorkspaceSkillSource}, {@code workspace/skills/}).</li>
 * </ul>
 * The workspace source has priority, so a user can override / shadow a built-in skill by dropping a
 * same-named skill into the workspace. With no built-in module on the classpath the classpath source
 * finds nothing and behaviour is exactly workspace-only (backward compatible).
 *
 * <p><b>Progressive loading.</b> {@link #listSkills()} reads only each skill's cheap
 * {@link Skill#metadata() metadata} (name + optional description), never a body; {@link #loadSkill}
 * reads the full body on demand and surfaces the skill's supporting files (bounded). The {@code @Tool}
 * surface ({@link #listSkills()} / {@link #loadSkill(String)}) — names, signatures and return
 * semantics — is unchanged: an empty listing is {@code "No skills found."}, a description-less skill is
 * {@code "- <name>"}, an unknown load is {@code "Skill not found: <name>"}, a read error is
 * {@code "Error: <msg>"}, and a skill with no supporting files returns exactly its body.
 */
public final class SkillsTool {

    private static final Logger log = LoggerFactory.getLogger(SkillsTool.class);

    private final SkillRegistry registry;
    private final SkillLimits limits;
    private final SkillUsageRecorder usageRecorder;

    /** Primary constructor: compose any set of sources (used by tests and explicit wiring). */
    public SkillsTool(SkillRegistry registry) {
        this(registry, SkillLimits.defaults());
    }

    public SkillsTool(SkillRegistry registry, SkillLimits limits) {
        this(registry, limits, SkillUsageRecorder.noop());
    }

    /**
     * Full constructor: additionally wire a {@link SkillUsageRecorder} (skill-curator-and-graded-
     * promotion, S3). The default overloads use {@link SkillUsageRecorder#noop()}, so usage recording
     * is off unless a native recorder is injected — zero behavior change to the {@code @Tool} surface.
     */
    public SkillsTool(SkillRegistry registry, SkillLimits limits, SkillUsageRecorder usageRecorder) {
        this.registry = registry;
        this.limits = limits == null ? SkillLimits.defaults() : limits;
        this.usageRecorder = usageRecorder == null ? SkillUsageRecorder.noop() : usageRecorder;
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

    /**
     * The composed {@link SkillRegistry} backing this tool. NOT a {@code @Tool} method — exposed so the
     * optional {@code skill_search} tool (skill-matching, S2) can rank the <em>same</em> skill set
     * {@code listSkills} lists (shared registry ⇒ ranking and listing never diverge).
     */
    public SkillRegistry registry() {
        return registry;
    }

    @Tool(description = "List available skills from the skills directory", readOnly = true)
    public String listSkills() {
        List<Skill> skills = registry.all();
        if (skills.isEmpty()) {
            return "No skills found.";
        }
        return skills.stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(this::formatListing)
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Load and read a skill's content by name", readOnly = true)
    public String loadSkill(@ToolParam(name = "skill_name", description = "Skill name") String skillName) {
        Optional<Skill> skill = registry.find(skillName);
        if (skill.isEmpty()) {
            return "Skill not found: " + skillName;
        }
        try {
            String body = skill.get().content();
            String rendered = body + SupportingFilesRenderer.render(safeSupportingFiles(skill.get()), limits);
            recordUsage(skill.get().name());
            return rendered;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Record a usage hit for a resolved skill (skill-curator-and-graded-promotion, S3). Fault-tolerant:
     * a recording failure is swallowed so it can never affect the {@code loadSkill} result. Default
     * recorder is a no-op, so this is inert unless the curator feature is wired on.
     */
    private void recordUsage(String skillName) {
        try {
            usageRecorder.record(skillName);
        } catch (RuntimeException e) {
            log.debug("Skill usage recording failed for '{}': {}", skillName, e.toString());
        }
    }

    /** Progressive: format from metadata only (never reads the body). */
    private String formatListing(Skill skill) {
        SkillMetadata meta = safeMetadata(skill);
        return meta.hasDescription() ? "- " + skill.name() + " — " + meta.description() : "- " + skill.name();
    }

    private SkillMetadata safeMetadata(Skill skill) {
        try {
            SkillMetadata m = skill.metadata();
            return m != null ? m : SkillMetadata.ofName(skill.name());
        } catch (RuntimeException e) {
            log.warn("Failed to read metadata for skill '{}': {}", skill.name(), e.toString());
            return SkillMetadata.ofName(skill.name());
        }
    }

    private List<SkillResource> safeSupportingFiles(Skill skill) {
        try {
            List<SkillResource> files = skill.supportingFiles();
            return files != null ? files : List.of();
        } catch (RuntimeException e) {
            log.warn("Failed to list supporting files for skill '{}': {}", skill.name(), e.toString());
            return List.of();
        }
    }
}
