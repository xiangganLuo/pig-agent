package io.pigagent.tool.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillRegistry}: multi-source composition — de-dup by name with the highest-priority source
 * winning (workspace over built-in), deterministic sorted listing, not-found resolution, and
 * fault-tolerant skipping of a throwing source.
 */
class SkillRegistryTest {

    /** The first (highest-priority) source wins a name collision. */
    @Test
    void find_highestPrioritySourceWins() {
        // Arrange — "shared" provided by both; workspace-like source listed first
        SkillSource high = () -> List.of(new FixedSkill("shared", "WORKSPACE"), new FixedSkill("w-only", "W"));
        SkillSource low = () -> List.of(new FixedSkill("shared", "BUILTIN"), new FixedSkill("b-only", "B"));
        SkillRegistry registry = new SkillRegistry(List.of(high, low));

        // Act / Assert — override + union
        assertThat(content(registry, "shared")).isEqualTo("WORKSPACE");
        assertThat(content(registry, "w-only")).isEqualTo("W");
        assertThat(content(registry, "b-only")).isEqualTo("B");
    }

    @Test
    void listNames_dedupedAndSorted() {
        // Arrange
        SkillSource high = () -> List.of(new FixedSkill("tdd", "1"), new FixedSkill("code-review", "2"));
        SkillSource low = () -> List.of(new FixedSkill("code-review", "dup"), new FixedSkill("planning", "3"));
        SkillRegistry registry = new SkillRegistry(List.of(high, low));

        // Act
        List<String> names = registry.listNames();

        // Assert — union, deduped, alphabetical
        assertThat(names).containsExactly("code-review", "planning", "tdd");
    }

    @Test
    void find_unknownName_returnsEmpty() {
        SkillRegistry registry = new SkillRegistry(List.of(() -> List.of(new FixedSkill("a", "x"))));

        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void all_faultTolerant_skipsThrowingSource() {
        // Arrange — first source throws, second is healthy
        SkillSource broken = () -> {
            throw new RuntimeException("boom");
        };
        SkillSource healthy = () -> List.of(new FixedSkill("ok", "content"));
        SkillRegistry registry = new SkillRegistry(List.of(broken, healthy));

        // Act / Assert — the healthy source's skill still surfaces
        assertThat(registry.listNames()).containsExactly("ok");
        assertThat(content(registry, "ok")).isEqualTo("content");
    }

    @Test
    void emptyRegistry_listsNothing() {
        assertThat(new SkillRegistry(null).listNames()).isEmpty();
        assertThat(new SkillRegistry(List.of()).find("x")).isEmpty();
    }

    private static String content(SkillRegistry registry, String name) {
        try {
            return registry.find(name).orElseThrow().content();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
