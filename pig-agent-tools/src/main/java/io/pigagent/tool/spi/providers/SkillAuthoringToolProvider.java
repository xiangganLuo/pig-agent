package io.pigagent.tool.spi.providers;

import io.pigagent.tool.skills.authoring.SkillAuthoringTool;
import io.pigagent.tool.skills.authoring.SkillContentScanner;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link SkillAuthoringTool} (the autonomous-skills write tools). Returns
 * {@code null} — registering nothing — when the staging area is not wired (autonomous skills off), so
 * the {@code proposeSkill}/{@code skillManage} tools appear only when the feature is enabled. The tool
 * additionally gates its own visibility via {@code ToolAvailability} on the live enabled flag.
 */
public final class SkillAuthoringToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        if (context.skillStaging() == null) {
            return null;
        }
        return new SkillAuthoringTool(
                context.skillStaging(),
                SkillContentScanner.defaults(),
                context.autonomousSkillsEnabled());
    }
}
