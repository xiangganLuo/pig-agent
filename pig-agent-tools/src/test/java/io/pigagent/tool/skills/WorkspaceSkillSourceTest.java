package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkspaceSkillSource}: lists workspace sub-directories that contain a {@code SKILL.md},
 * tolerates a missing/empty directory, and reads content lazily from the backing file.
 */
class WorkspaceSkillSourceTest {

    @Test
    void discover_listsOnlyDirsContainingSkillMd(@TempDir Path dir) throws IOException {
        // Arrange — a, b are real skills; c is an incomplete directory (no SKILL.md)
        writeSkill(dir, "a", "alpha");
        writeSkill(dir, "b", "beta");
        Files.createDirectory(dir.resolve("c"));

        // Act
        List<Skill> skills = new WorkspaceSkillSource(dir).discover();

        // Assert
        assertThat(skills).extracting(Skill::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void fileSkill_contentReadsBackingFile(@TempDir Path dir) throws IOException {
        // Arrange
        writeSkill(dir, "code-review", "# Code Review\nbody");

        // Act
        Skill skill = new WorkspaceSkillSource(dir).discover().get(0);

        // Assert
        assertThat(skill.content()).isEqualTo("# Code Review\nbody");
    }

    @Test
    void discover_missingDirectory_returnsEmpty(@TempDir Path dir) {
        // Arrange — a path that does not exist
        Path missing = dir.resolve("does-not-exist");

        // Act / Assert
        assertThat(new WorkspaceSkillSource(missing).discover()).isEmpty();
    }

    @Test
    void discover_nullDirectory_returnsEmpty() {
        assertThat(new WorkspaceSkillSource(null).discover()).isEmpty();
    }

    @Test
    void discover_emptyDirectory_returnsEmpty(@TempDir Path dir) {
        assertThat(new WorkspaceSkillSource(dir).discover()).isEmpty();
    }

    private static void writeSkill(Path root, String name, String content) throws IOException {
        Path skillDir = Files.createDirectories(root.resolve(name));
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }
}
