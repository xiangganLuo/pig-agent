package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillManifestParser} (the default {@link FrontMatterManifestParser}): tolerant parsing of an
 * optional {@code ---}-fenced YAML front-matter — present (full/partial), absent (derived), malformed
 * (no closing fence → whole text is body), plus keyword inline/block forms and body stripping.
 */
class SkillManifestParserTest {

    private final SkillManifestParser parser = SkillManifestParser.defaults();

    @Test
    void fullFrontMatter_parsedAndBodyStripped() {
        // Arrange
        String text = """
                ---
                name: code-review
                description: Review a diff.
                keywords: review, quality, diff
                version: 2.1.0
                ---
                # Code Review
                body line
                """;

        // Act
        SkillManifest manifest = parser.parse(text, "dir-name");

        // Assert
        SkillMetadata meta = manifest.metadata();
        assertThat(meta.name()).isEqualTo("code-review");
        assertThat(meta.description()).isEqualTo("Review a diff.");
        assertThat(meta.keywords()).containsExactly("review", "quality", "diff");
        assertThat(meta.version()).isEqualTo("2.1.0");
        assertThat(manifest.body()).startsWith("# Code Review");
        assertThat(manifest.body()).doesNotContain("keywords:");
    }

    @Test
    void noFrontMatter_derivesNameAndDescription() {
        // Arrange — plain SKILL.md, no front-matter
        String text = "# Systematic Debugging\n\nA disciplined loop for bugs.\n";

        // Act
        SkillManifest manifest = parser.parse(text, "systematic-debugging");

        // Assert — name from fallback (dir), description derived from the first (heading) line
        assertThat(manifest.metadata().name()).isEqualTo("systematic-debugging");
        assertThat(manifest.metadata().description()).isEqualTo("Systematic Debugging");
        assertThat(manifest.metadata().keywords()).isEmpty();
        assertThat(manifest.body()).isEqualTo(text); // body unchanged when no front-matter
    }

    @Test
    void partialFrontMatter_missingKeysAreEmpty() {
        // Arrange — only name present
        String text = "---\nname: tdd\n---\n# TDD\ndetail\n";

        // Act
        SkillMetadata meta = parser.parse(text, "fallback").metadata();

        // Assert — name honoured; description derived from body; version empty; keywords empty
        assertThat(meta.name()).isEqualTo("tdd");
        assertThat(meta.version()).isEmpty();
        assertThat(meta.keywords()).isEmpty();
        assertThat(meta.description()).isEqualTo("TDD");
    }

    @Test
    void malformedFrontMatter_noClosingFence_treatedAsBody() {
        // Arrange — opening fence but never closed
        String text = "---\nname: broken\n# Title\nstill body\n";

        // Act
        SkillManifest manifest = parser.parse(text, "broken-dir");

        // Assert — tolerant: the whole text is the body, metadata derived, never throws
        assertThat(manifest.body()).isEqualTo(text);
        assertThat(manifest.metadata().name()).isEqualTo("broken-dir");
        assertThat(manifest.metadata().keywords()).isEmpty();
    }

    @Test
    void keywords_blockListForm_parsed() {
        // Arrange — YAML block list under keywords:
        String text = """
                ---
                name: x
                keywords:
                  - alpha
                  - beta
                when-to-use: gamma, delta
                ---
                # X
                """;

        // Act
        SkillMetadata meta = parser.parse(text, "x").metadata();

        // Assert — block list + when-to-use merged into keywords
        assertThat(meta.keywords()).containsExactly("alpha", "beta", "gamma", "delta");
    }

    @Test
    void quotedValues_areUnwrapped() {
        // Arrange
        String text = "---\nname: \"quoted-name\"\ndescription: 'single quoted'\n---\n# Title\n";

        // Act
        SkillMetadata meta = parser.parse(text, "fallback").metadata();

        // Assert
        assertThat(meta.name()).isEqualTo("quoted-name");
        assertThat(meta.description()).isEqualTo("single quoted");
    }

    @Test
    void nullText_isTolerated() {
        // Act
        SkillManifest manifest = parser.parse(null, "fallback");

        // Assert — no throw; empty body; fallback name
        assertThat(manifest.body()).isEmpty();
        assertThat(manifest.metadata().name()).isEqualTo("fallback");
    }
}
