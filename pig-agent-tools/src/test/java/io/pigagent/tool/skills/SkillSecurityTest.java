package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SkillSecurity}: real-path containment / direct-child judgement that catches {@code ../}
 * traversal and symlink escapes. Symlink cases are skipped when the platform cannot create a symlink
 * (Windows without privilege); the traversal/containment logic is exercised regardless.
 */
class SkillSecurityTest {

    @Test
    void isWithin_trueForChild_falseForSibling(@TempDir Path base) throws IOException {
        // Arrange
        Path root = Files.createDirectories(base.resolve("skills"));
        Path child = Files.createDirectories(root.resolve("code-review"));
        Path sibling = Files.createDirectories(base.resolve("outside"));

        // Act / Assert
        assertThat(SkillSecurity.isWithin(root, child)).isTrue();
        assertThat(SkillSecurity.isWithin(root, root)).isTrue();
        assertThat(SkillSecurity.isWithin(root, sibling)).isFalse();
    }

    @Test
    void isWithin_traversalEscape_isFalse(@TempDir Path base) throws IOException {
        // Arrange — a normalized ../ escaping the root
        Path root = Files.createDirectories(base.resolve("skills"));
        Files.createDirectories(base.resolve("secret"));
        Path traversal = root.resolve("..").resolve("secret");

        // Act / Assert — normalized real path lands outside the root
        assertThat(SkillSecurity.isWithin(root, traversal)).isFalse();
    }

    @Test
    void isDirectChild_onlyImmediateChildren(@TempDir Path base) throws IOException {
        // Arrange
        Path root = Files.createDirectories(base.resolve("skills"));
        Path child = Files.createDirectories(root.resolve("a"));
        Path grandchild = Files.createDirectories(child.resolve("nested"));

        // Act / Assert
        assertThat(SkillSecurity.isDirectChild(root, child)).isTrue();
        assertThat(SkillSecurity.isDirectChild(root, grandchild)).isFalse();
    }

    @Test
    void symlinkEscape_isDetected(@TempDir Path base) throws IOException {
        // Arrange — a symlink inside the root that points outside it
        Path root = Files.createDirectories(base.resolve("skills"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Path link = root.resolve("evil");
        assumeTrue(canSymlink(link, outside), "platform cannot create symlinks");

        // Act / Assert — resolved real path escapes the root, so neither within nor a direct child
        assertThat(SkillSecurity.isWithin(root, link)).isFalse();
        assertThat(SkillSecurity.isDirectChild(root, link)).isFalse();
    }

    @Test
    void nullArguments_areFalse() {
        assertThat(SkillSecurity.isWithin(null, null)).isFalse();
        assertThat(SkillSecurity.isDirectChild(null, null)).isFalse();
        assertThat(SkillSecurity.realOrNormalized(null)).isNull();
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
