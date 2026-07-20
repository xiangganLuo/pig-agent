package io.pigagent.tool.skills;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SkillSource} that adopts an AgentScope 2.0 native {@code AgentSkillRepository} as a pure
 * <b>library</b> ("engine, not mouth"): it reads {@code repo.getAllSkills()} and adapts each native
 * {@code AgentSkill} into a pig {@link Skill} via {@link NativeAgentSkill}, then hands them to the
 * existing {@link SkillRegistry} for de-duplication. It deliberately does NOT re-open the native
 * dynamic-skill prompt path ({@code <available_skills>} injection / {@code load_skill_through_path}) —
 * pig keeps {@code disableDynamicSkills()} and surfaces skills only through its own
 * {@code SkillsTool.listSkills}/{@code loadSkill}.
 *
 * <p>Mirrors {@link WorkspaceSkillSource}/{@link ClasspathSkillSource}: discovery is <b>fault-tolerant</b>
 * (a throwing repository degrades to "no skills", never propagates) and a reserved dot-prefixed skill
 * name (e.g. the autonomous-skills {@code .pending}/{@code .archive} staging dirs) is skipped, so a
 * staged draft can never leak into {@code listSkills} through the native source.
 */
public final class NativeRepositorySkillSource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(NativeRepositorySkillSource.class);

    private final AgentSkillRepository repository;

    /** Primary: adopt any native repository (tests supply a fake; wiring supplies the real one). */
    public NativeRepositorySkillSource(AgentSkillRepository repository) {
        this.repository = repository;
    }

    /** Convenience: adopt a native {@link FileSystemSkillRepository} over a skills directory. */
    public NativeRepositorySkillSource(Path skillsDir) {
        this(new FileSystemSkillRepository(skillsDir));
    }

    @Override
    public String name() {
        try {
            return "native:" + repository.getSource();
        } catch (RuntimeException e) {
            return "native";
        }
    }

    @Override
    public List<Skill> discover() {
        List<Skill> out = new ArrayList<>();
        if (repository == null) {
            return out;
        }
        try {
            List<AgentSkill> skills = repository.getAllSkills();
            if (skills == null) {
                return out;
            }
            for (AgentSkill skill : skills) {
                if (skill == null) {
                    continue;
                }
                String name = skill.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (name.startsWith(".")) {
                    // Reserved dot-prefixed name (staging/archive) — never surface (mirrors
                    // WorkspaceSkillSource) so a staged draft can't leak into listSkills.
                    log.warn("Skipping native skill with reserved dot-prefixed name '{}'", name);
                    continue;
                }
                out.add(new NativeAgentSkill(skill));
            }
        } catch (Throwable t) {
            log.warn("Native skill repository discovery failed, skipping: {}", t.toString());
        }
        return out;
    }
}
