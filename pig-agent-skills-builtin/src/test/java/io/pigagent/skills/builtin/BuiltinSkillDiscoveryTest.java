package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.ClasspathSkillSource;
import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.spi.SkillProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: the built-in skills are discovered purely by the {@code META-INF/services} declaration
 * (classpath {@link ClasspathSkillSource} via {@link ServiceLoader}) — proving "ship a provider + a
 * service line" wiring with no central assembly edit. Every curated skill's {@code SKILL.md} is
 * discovered and non-empty.
 */
class BuiltinSkillDiscoveryTest {

    @Test
    void serviceLoader_findsBuiltinSkillProvider() {
        // Act
        List<SkillProvider> providers = ServiceLoader.load(SkillProvider.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        // Assert
        assertThat(providers).anyMatch(p -> p instanceof BuiltinSkillProvider);
    }

    @Test
    void classpathSource_discoversAllBuiltinSkills() throws IOException {
        // Act — the built-in source used by SkillsTool at runtime
        List<Skill> skills = new ClasspathSkillSource().discover();
        Set<String> names = skills.stream().map(Skill::name).collect(Collectors.toSet());

        // Assert — all curated skills discovered, each with non-empty content
        assertThat(names).containsAll(SkillCatalog.SKILL_NAMES);
        for (Skill skill : skills) {
            if (SkillCatalog.SKILL_NAMES.contains(skill.name())) {
                assertThat(skill.content()).as("content of %s", skill.name()).isNotBlank();
            }
        }
    }
}
