package io.pigagent.tool.skills;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NativeRepositorySkillSource}: adopts a native {@code AgentSkillRepository} behind pig's
 * {@link SkillSource} seam — adapts {@code getAllSkills()} into pig {@link Skill}s, is fault-tolerant,
 * and skips reserved dot-prefixed names so staged drafts never leak.
 */
class NativeRepositorySkillSourceTest {

    @Test
    void discover_adaptsSkillsFromRealFileSystemRepo(@TempDir Path ws) throws IOException {
        // Arrange — a real native FileSystemSkillRepository over pig's skills layout.
        Path skills = Files.createDirectories(ws.resolve("skills"));
        writeSkill(skills.resolve("alpha"), "---\nname: alpha\ndescription: the alpha\n---\n# Alpha\nbody\n");
        NativeRepositorySkillSource src =
                new NativeRepositorySkillSource(new FileSystemSkillRepository(skills));

        // Act
        List<Skill> found = src.discover();

        // Assert — the native skill is adapted into a pig Skill (name/description/body).
        assertThat(found).extracting(Skill::name).contains("alpha");
        Skill alpha = found.stream().filter(s -> "alpha".equals(s.name())).findFirst().orElseThrow();
        assertThat(alpha.metadata().description()).isEqualTo("the alpha");
        assertThat(alpha.content()).contains("# Alpha");
    }

    @Test
    void discover_faultTolerant_whenRepoThrows() {
        // Arrange — a repository whose getAllSkills() throws.
        AgentSkillRepository bad = new FakeRepo("bad", () -> {
            throw new RuntimeException("boom");
        });

        // Act + Assert — degrades to "no skills", never propagates.
        assertThat(new NativeRepositorySkillSource(bad).discover()).isEmpty();
    }

    @Test
    void discover_skipsDotPrefixedNames() {
        // Arrange — a repository that (hypothetically) returns a staged dot-prefixed skill.
        AgentSkillRepository repo = new FakeRepo("t", () -> List.of(
                AgentSkill.builder().name(".pending").description("x").skillContent("# x\n").build(),
                AgentSkill.builder().name("real").description("y").skillContent("# y\n").build()));

        // Act
        List<Skill> found = new NativeRepositorySkillSource(repo).discover();

        // Assert — the reserved dot-prefixed name is skipped (staging invariant preserved).
        assertThat(found).extracting(Skill::name).containsExactly("real");
    }

    @Test
    void name_prefixedWithNativeSource() {
        AgentSkillRepository repo = new FakeRepo("myrepo", List::of);
        assertThat(new NativeRepositorySkillSource(repo).name()).isEqualTo("native:myrepo");
    }

    private static void writeSkill(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    /** Minimal fake: {@code getAllSkills()} via a supplier (may throw); the rest are inert. */
    private static final class FakeRepo implements AgentSkillRepository {
        private final String source;
        private final Supplier<List<AgentSkill>> all;

        FakeRepo(String source, Supplier<List<AgentSkill>> all) {
            this.source = source;
            this.all = all;
        }

        @Override public AgentSkill getSkill(String name) { return null; }
        @Override public List<String> getAllSkillNames() { return List.of(); }
        @Override public List<AgentSkill> getAllSkills() { return all.get(); }
        @Override public boolean save(List<AgentSkill> skills, boolean overwrite) { return false; }
        @Override public boolean delete(String name) { return false; }
        @Override public boolean skillExists(String name) { return false; }
        @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return null; }
        @Override public String getSource() { return source; }
        @Override public void setWriteable(boolean writeable) { }
        @Override public boolean isWriteable() { return false; }
    }
}
