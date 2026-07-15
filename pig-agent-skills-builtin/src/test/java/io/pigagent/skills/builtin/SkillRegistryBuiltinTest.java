package io.pigagent.skills.builtin;

import io.pigagent.tool.skills.ClasspathSkillSource;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end composition against the <b>real</b> built-in skills: a {@link SkillsTool} wired with a
 * workspace source (highest priority) over the classpath built-in source lists every built-in skill,
 * loads built-in content for a built-in-only skill, and returns the workspace version when a user
 * shadows a same-named built-in skill.
 */
class SkillRegistryBuiltinTest {

    /** Compose workspace (priority) over the real built-in classpath source, as production does. */
    private static SkillsTool toolOver(Path workspaceSkillsDir) {
        return new SkillsTool(new SkillRegistry(List.of(
                new WorkspaceSkillSource(workspaceSkillsDir),
                new ClasspathSkillSource())));
    }

    @Test
    void listSkills_includesAllBuiltinNames(@TempDir Path workspace) {
        // Arrange — empty workspace, only built-in skills present
        SkillsTool tool = toolOver(workspace);

        // Act
        String listing = tool.listSkills();

        // Assert — every curated built-in skill shows up
        for (String name : SkillCatalog.SKILL_NAMES) {
            assertThat(listing).contains("- " + name);
        }
    }

    @Test
    void loadSkill_returnsBuiltinContent_forBuiltinOnlySkill(@TempDir Path workspace) {
        // Arrange
        SkillsTool tool = toolOver(workspace);

        // Act
        String content = tool.loadSkill("tdd");

        // Assert — real built-in guide content
        assertThat(content).startsWith("# Test-Driven Development");
    }

    @Test
    void workspaceEntry_overridesSameNamedBuiltin(@TempDir Path workspace) throws IOException {
        // Arrange — user drops a same-named skill into the workspace
        Path dir = Files.createDirectories(workspace.resolve("code-review"));
        Files.writeString(dir.resolve("SKILL.md"), "# My custom code review\noverridden");
        SkillsTool tool = toolOver(workspace);

        // Act / Assert — workspace shadows the built-in, and the name is listed once (the listing now
        // carries a derived description after the name, so match on the "- <name>" prefix)
        assertThat(tool.loadSkill("code-review")).isEqualTo("# My custom code review\noverridden");
        assertThat(count(tool.listSkills(), "- code-review")).isEqualTo(1);
    }

    @Test
    void unknownSkill_returnsNotFound(@TempDir Path workspace) {
        SkillsTool tool = toolOver(workspace);

        assertThat(tool.loadSkill("no-such-skill")).isEqualTo("Skill not found: no-such-skill");
    }

    private static long count(String haystack, String needle) {
        return haystack.lines().filter(line -> line.equals(needle) || line.startsWith(needle + " ")).count();
    }
}
