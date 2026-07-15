package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileSkill}: progressive reads (cheap metadata from the head, full body on demand), front-matter
 * stripping in {@code content()}, backward-compatible content for a plain {@code SKILL.md}, tolerant
 * metadata on a missing file, and bounded supporting-file enumeration confined to the skill directory.
 */
class FileSkillTest {

    private static FileSkill skill(Path dir) {
        return new FileSkill(dir.getFileName().toString(), dir, SkillLimits.defaults());
    }

    @Test
    void metadata_parsedFromFrontMatter(@TempDir Path base) throws IOException {
        // Arrange
        Path dir = Files.createDirectories(base.resolve("code-review"));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: code-review\ndescription: Review a diff.\nkeywords: a, b\n---\n# Code Review\nbody\n");

        // Act
        SkillMetadata meta = skill(dir).metadata();

        // Assert
        assertThat(meta.name()).isEqualTo("code-review");
        assertThat(meta.description()).isEqualTo("Review a diff.");
        assertThat(meta.keywords()).containsExactly("a", "b");
    }

    @Test
    void content_stripsFrontMatter_bodyStartsWithHeading(@TempDir Path base) throws IOException {
        // Arrange
        Path dir = Files.createDirectories(base.resolve("tdd"));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: tdd\ndescription: d\n---\n# Test-Driven Development\ndetail\n");

        // Act
        String body = skill(dir).content();

        // Assert — front-matter gone, body starts with the heading
        assertThat(body).startsWith("# Test-Driven Development");
        assertThat(body).doesNotContain("description:");
    }

    @Test
    void content_noFrontMatter_isVerbatim(@TempDir Path base) throws IOException {
        // Arrange — a plain SKILL.md, no front-matter (backward compatibility)
        Path dir = Files.createDirectories(base.resolve("plain"));
        Files.writeString(dir.resolve("SKILL.md"), "# Plain\nbody");

        // Act / Assert — byte-identical
        assertThat(skill(dir).content()).isEqualTo("# Plain\nbody");
    }

    @Test
    void metadata_missingFile_fallsBackToName(@TempDir Path base) throws IOException {
        // Arrange — directory exists but SKILL.md does not
        Path dir = Files.createDirectories(base.resolve("ghost"));

        // Act / Assert — no throw; name-only fallback
        SkillMetadata meta = skill(dir).metadata();
        assertThat(meta.name()).isEqualTo("ghost");
        assertThat(meta.description()).isEmpty();
    }

    @Test
    void supportingFiles_listSiblings_excludeSkillMd(@TempDir Path base) throws IOException {
        // Arrange
        Path dir = Files.createDirectories(base.resolve("bundle"));
        Files.writeString(dir.resolve("SKILL.md"), "# Bundle\nbody");
        Files.writeString(dir.resolve("template.txt"), "hello");
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data").resolve("example.json"), "{}");

        // Act
        List<SkillResource> files = skill(dir).supportingFiles();

        // Assert — both supporting files, SKILL.md excluded, relative paths use forward slashes
        assertThat(files).extracting(SkillResource::path)
                .containsExactly("data/example.json", "template.txt");
        assertThat(files).noneMatch(r -> r.path().equals("SKILL.md"));
    }

    @Test
    void supportingFiles_none_returnsEmpty(@TempDir Path base) throws IOException {
        // Arrange — SKILL.md only
        Path dir = Files.createDirectories(base.resolve("solo"));
        Files.writeString(dir.resolve("SKILL.md"), "# Solo\nbody");

        // Act / Assert
        assertThat(skill(dir).supportingFiles()).isEmpty();
    }
}
