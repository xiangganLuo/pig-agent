package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WorkspaceSkillSource skips dot-prefixed reserved dirs (.pending staging / .archive). */
class WorkspaceSkillSourceReservedDirTest {

    @TempDir
    Path skillsDir;

    @Test
    void reservedDotDirectoriesAreNotSurfacedAsSkills() throws IOException {
        writeSkill(skillsDir.resolve("good"), "# Good\ncontent");
        // A staged draft one level deep AND its reserved parent laid out as a "skill" directly.
        writeSkill(skillsDir.resolve(".pending").resolve("draft"), "# Draft\nstaged");
        writeSkill(skillsDir.resolve(".pending"), "# Pending as skill?\nno");
        writeSkill(skillsDir.resolve(".archive"), "# Archived\nno");

        List<Skill> skills = new WorkspaceSkillSource(skillsDir).discover();
        List<String> names = skills.stream().map(Skill::name).toList();

        assertThat(names).contains("good");
        assertThat(names).doesNotContain(".pending", ".archive", "draft");
    }

    private static void writeSkill(Path dir, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), body);
    }
}
