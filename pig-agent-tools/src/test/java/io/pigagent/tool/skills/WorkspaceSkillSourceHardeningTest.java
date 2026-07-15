package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WorkspaceSkillSource} security hardening: an oversized {@code SKILL.md}, a symlinked skill
 * directory escaping the root, and a symlinked supporting file escaping its directory are all rejected
 * / excluded — with a {@code warn}, never a crash — while healthy skills and their supporting files
 * are still discovered. Symlink cases are skipped where the platform cannot create symlinks.
 */
class WorkspaceSkillSourceHardeningTest {

    private static void writeSkill(Path root, String name, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    @Test
    void oversizedSkillMd_isSkipped_healthyKept(@TempDir Path root) throws IOException {
        // Arrange — a 10-byte cap; "big" exceeds it, "small" is under it
        writeSkill(root, "big", "x".repeat(50));
        writeSkill(root, "small", "# ok");
        SkillLimits tight = new SkillLimits(10, 0, 0, 0);

        // Act
        List<Skill> skills = new WorkspaceSkillSource(root, tight).discover();

        // Assert — oversized skill skipped, healthy one kept, no throw
        assertThat(skills).extracting(Skill::name).containsExactly("small");
    }

    @Test
    void symlinkedSkillDirEscapingRoot_isSkipped(@TempDir Path base) throws IOException {
        // Arrange — a real skill + a symlinked "skill" dir pointing outside the root
        Path root = Files.createDirectories(base.resolve("skills"));
        writeSkill(root, "real", "# Real\nbody");
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("SKILL.md"), "# Evil\nbody");
        Path link = root.resolve("evil");
        assumeTrue(canSymlink(link, outside), "platform cannot create symlinks");

        // Act
        List<Skill> skills = new WorkspaceSkillSource(root).discover();

        // Assert — the escaping symlink is dropped, the real skill survives
        assertThat(skills).extracting(Skill::name).containsExactly("real");
    }

    @Test
    void symlinkedSupportingFileEscaping_isExcluded(@TempDir Path base) throws IOException {
        // Arrange — a real skill dir with a supporting-file symlink pointing outside
        Path root = Files.createDirectories(base.resolve("skills"));
        Path dir = Files.createDirectories(root.resolve("bundle"));
        Files.writeString(dir.resolve("SKILL.md"), "# Bundle\nbody");
        Files.writeString(dir.resolve("ok.txt"), "inside");
        Path secret = Files.writeString(base.resolve("secret.txt"), "outside");
        Path link = dir.resolve("leak.txt");
        assumeTrue(canSymlink(link, secret), "platform cannot create symlinks");

        // Act
        Skill skill = new WorkspaceSkillSource(root).discover().get(0);
        List<SkillResource> files = skill.supportingFiles();

        // Assert — the escaping symlink is excluded; the in-dir file remains
        assertThat(files).extracting(SkillResource::path).contains("ok.txt");
        assertThat(files).extracting(SkillResource::path).doesNotContain("leak.txt");
    }

    @Test
    void healthySkill_exposesSupportingFiles(@TempDir Path root) throws IOException {
        // Arrange
        Path dir = Files.createDirectories(root.resolve("bundle"));
        Files.writeString(dir.resolve("SKILL.md"), "# Bundle\nbody");
        Files.writeString(dir.resolve("template.md"), "template");

        // Act
        Skill skill = new WorkspaceSkillSource(root).discover().get(0);

        // Assert
        assertThat(skill.supportingFiles()).extracting(SkillResource::path).containsExactly("template.md");
    }

    private static boolean canSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
