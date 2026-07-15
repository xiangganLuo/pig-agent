package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.spi.SkillProvider;

import java.util.List;

/**
 * The {@link SkillProvider} that exposes this module's curated built-in skills to the runtime. A
 * single provider contributes all of {@link SkillCatalog#all()} (a skill is just a {@code SKILL.md}
 * resource, so there is no need for one SPI entry per skill). Declared in
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider} so {@code ClasspathSkillSource}
 * discovers it out of the box.
 */
public final class BuiltinSkillProvider implements SkillProvider {

    @Override
    public List<Skill> skills() {
        return SkillCatalog.all();
    }
}
