package io.pigagent.tool.skills;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration of {@link NativeRepositorySkillSource} into the read stack via {@link SkillRegistry}
 * (native-skill-engine-bridge, D4): the native source sits at LOWEST priority behind pig's sources, so
 * adding it over the SAME skills root leaves {@code listSkills} equivalent (same-name skills are folded
 * away by de-dup, pig sources win), while a native source over a DIFFERENT root is purely additive.
 */
class NativeSkillSourceDedupTest {

    @Test
    void sameRootNativeSource_isFoldedAway_behaviorEquivalent(@TempDir Path ws) throws IOException {
        // Arrange — pig's skills dir with a front-matter skill and a bare skill.
        Path skills = Files.createDirectories(ws.resolve("skills"));
        writeSkill(skills.resolve("alpha"), "---\nname: alpha\ndescription: a\n---\n# Alpha\n");
        writeSkill(skills.resolve("beta"), "# Beta\nbody\n");

        // Act — baseline (workspace + classpath) vs. with a same-root native source appended.
        List<String> baseline = new SkillRegistry(List.of(
                new WorkspaceSkillSource(skills), new ClasspathSkillSource())).listNames();
        List<String> withNative = new SkillRegistry(List.of(
                new WorkspaceSkillSource(skills), new ClasspathSkillSource(),
                new NativeRepositorySkillSource(new FileSystemSkillRepository(skills)))).listNames();

        // Assert — enabling the same-root native source changes nothing (de-dup fold; D4 equivalence).
        assertThat(withNative).as("same-root native source folds away → listSkills unchanged")
                .isEqualTo(baseline)
                .contains("alpha", "beta");
    }

    @Test
    void differentRootNativeSource_isAdditive_butPigWinsNameCollision(@TempDir Path ws) throws IOException {
        // Arrange — pig workspace dir and a SEPARATE native repo dir with an overlapping name + an extra.
        Path pigSkills = Files.createDirectories(ws.resolve("skills"));
        writeSkill(pigSkills.resolve("shared"), "---\nname: shared\ndescription: PIG version\n---\n# Shared\n");
        Path nativeSkills = Files.createDirectories(ws.resolve("native-skills"));
        writeSkill(nativeSkills.resolve("shared"),
                "---\nname: shared\ndescription: NATIVE version\n---\n# Shared native\n");
        writeSkill(nativeSkills.resolve("extra"), "---\nname: extra\ndescription: only native\n---\n# Extra\n");

        SkillRegistry registry = new SkillRegistry(List.of(
                new WorkspaceSkillSource(pigSkills),
                new ClasspathSkillSource(),
                new NativeRepositorySkillSource(new FileSystemSkillRepository(nativeSkills))));

        // Assert — native contributes a genuinely new skill ("extra"), but on a name collision the
        // higher-priority pig workspace source wins ("shared" resolves to the PIG version).
        assertThat(registry.listNames()).contains("shared", "extra");
        assertThat(registry.find("shared")).isPresent();
        assertThat(registry.find("shared").orElseThrow().metadata().description()).isEqualTo("PIG version");
    }

    private static void writeSkill(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
