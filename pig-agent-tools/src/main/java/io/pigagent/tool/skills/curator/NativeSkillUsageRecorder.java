package io.pigagent.tool.skills.curator;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * {@link SkillUsageRecorder} backed by the AgentScope 2.0 native {@link SkillUsageStore} (adopted as a
 * pure library over a {@link LocalFilesystem} rooted at the workspace). A {@code loadSkill} hit becomes
 * a native {@code bumpUse}; a promotion becomes a {@code markAgentCreated}.
 *
 * <p>Native {@code bumpUse} is <b>provenance-gated</b> (it silently skips a skill that has no record
 * or was not agent-created) and internally does a locked load→mutate→save, so recording an unknown /
 * hand-authored skill is a safe no-op — a promoted (agent-created) skill is what actually ages. Every
 * call is wrapped fault-tolerant: a usage-store failure is logged at debug and swallowed so it can
 * never affect the {@code loadSkill} result (the "engine, not mouth" self-feed must be invisible).
 */
public final class NativeSkillUsageRecorder implements SkillUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(NativeSkillUsageRecorder.class);
    private static final String AGENT_CREATOR = "agent";

    private final SkillUsageStore store;

    /** Primary: adopt any native usage store (tests supply one over a temp filesystem). */
    public NativeSkillUsageRecorder(SkillUsageStore store) {
        this.store = store;
    }

    /** Convenience: build a usage store over a {@link LocalFilesystem} rooted at the workspace. */
    public NativeSkillUsageRecorder(Path workspaceRoot) {
        this(new SkillUsageStore(new LocalFilesystem(workspaceRoot)));
    }

    @Override
    public void record(String skillName) {
        if (store == null || skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            store.bumpUse(skillName);
        } catch (Throwable t) {
            log.debug("Skill usage record failed for '{}': {}", skillName, t.toString());
        }
    }

    @Override
    public void markCreated(String skillName) {
        if (store == null || skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            store.markAgentCreated(skillName, AGENT_CREATOR, java.util.List.of());
        } catch (Throwable t) {
            log.debug("Skill markCreated failed for '{}': {}", skillName, t.toString());
        }
    }
}
