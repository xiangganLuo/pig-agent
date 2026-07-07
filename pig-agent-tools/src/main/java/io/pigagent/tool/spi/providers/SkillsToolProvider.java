package io.pigagent.tool.spi.providers;

import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for {@link SkillsTool} (needs the skills directory from the context). */
public final class SkillsToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new SkillsTool(context.skillsDir());
    }
}
