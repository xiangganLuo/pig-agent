package io.pigagent.tool.skills;

import io.pigagent.tool.skills.spi.SkillProvider;

import java.util.List;

/**
 * A test-scoped {@link SkillProvider} declared in the test {@code META-INF/services} file, so
 * {@link ClasspathSkillSource} discovers it purely by "declaring a service line" — mirroring how a
 * real built-in skill module ships. Contributes one skill named {@code sample}.
 */
public final class SampleSkillProvider implements SkillProvider {

    @Override
    public List<Skill> skills() {
        return List.of(new FixedSkill("sample", "# Sample\nclasspath-discovered skill"));
    }
}
