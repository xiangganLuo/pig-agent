package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClasspathSkillSource}: a {@code SkillProvider} declared only in the test-scoped
 * {@code META-INF/services/io.pigagent.tool.skills.spi.SkillProvider} file is discovered purely by
 * "declaring a service line" — proving the classpath discovery path without depending on the
 * built-in skills module.
 */
class ClasspathSkillSourceTest {

    @Test
    void discover_findsProviderDeclaredInServicesFile() {
        // Act
        List<Skill> skills = new ClasspathSkillSource().discover();

        // Assert — the test-scoped SampleSkillProvider's skill is found
        assertThat(skills).extracting(Skill::name).contains("sample");
    }

    @Test
    void discover_isFaultTolerantWithForeignClassLoader() {
        // Arrange — an isolated classloader with no SkillProvider services on it
        ClassLoader empty = new ClassLoader(null) {
        };

        // Act / Assert — no providers → empty, never throws (module-absent degradation)
        assertThat(new ClasspathSkillSource(empty).discover()).isEmpty();
    }
}
