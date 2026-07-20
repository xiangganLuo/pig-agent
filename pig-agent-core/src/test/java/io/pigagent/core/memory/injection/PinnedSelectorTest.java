package io.pigagent.core.memory.injection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for {@link PinnedSelector} (capability {@code memory-retrieval-injection}):
 * deterministic heading-section extraction, {@code max-chars} truncation, graceful degradation to an
 * empty pinned block when the heading is absent, and the HEAD fallback source.
 */
class PinnedSelectorTest {

    private static final String MEMORY = String.join("\n",
            "# Memory",
            "",
            "## Pinned",
            "- User's name is 罗湘赣",
            "- Always reply in Chinese",
            "",
            "## Facts",
            "- The user prefers dark mode",
            "- The project uses Java 17");

    @Test
    void headingSource_extractsOnlyTheNamedSection() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 800);

        String pinned = PinnedSelector.select(MEMORY, s);

        assertThat(pinned).contains("罗湘赣").contains("Always reply in Chinese");
        assertThat(pinned).doesNotContain("dark mode").doesNotContain("Java 17");
    }

    @Test
    void headingAbsent_degradesToEmpty() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Nope", 800);

        String pinned = PinnedSelector.select(MEMORY, s);

        assertThat(pinned).isEmpty();
    }

    @Test
    void maxChars_truncatesDeterministically() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 10);

        String pinned = PinnedSelector.select(MEMORY, s);

        assertThat(pinned.length()).isLessThanOrEqualTo(10);
    }

    @Test
    void headSource_returnsLeadingChars() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEAD, "Pinned", 20);

        String pinned = PinnedSelector.select(MEMORY, s);

        assertThat(pinned).isNotEmpty();
        assertThat(pinned.length()).isLessThanOrEqualTo(20);
        assertThat(MEMORY.strip()).startsWith(pinned);
    }

    @Test
    void blankOrNullContent_returnsEmpty() {
        MemoryInjectionSettings s = MemoryInjectionSettings.defaults();

        assertThat(PinnedSelector.select(null, s)).isEmpty();
        assertThat(PinnedSelector.select("   \n  ", s)).isEmpty();
    }

    @Test
    void isStableAcrossCalls() {
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 800);

        assertThat(PinnedSelector.select(MEMORY, s)).isEqualTo(PinnedSelector.select(MEMORY, s));
    }
}
