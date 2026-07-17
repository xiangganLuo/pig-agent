package io.pigagent.cli.repl.select;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure {@link InlineSelector#truncateToWidth} helper: it bounds an option to the terminal width so
 * a wide model label can't wrap to a second row (which corrupts the cursor-up redraw). ANSI escapes are
 * preserved (zero visible width) and surrogate pairs are never split.
 */
class InlineSelectorTest {

    private static final String ESC = "";

    @Test
    void shortOption_returnedUnchanged() {
        assertThat(InlineSelector.truncateToWidth("gpt-4o", 40)).isEqualTo("gpt-4o");
    }

    @Test
    void longPlainOption_truncatedWithEllipsis() {
        String out = InlineSelector.truncateToWidth("a".repeat(100), 10);

        // 10 visible columns + the ellipsis marker; well under the raw length (no wrap).
        assertThat(out).startsWith("aaaaaaaaaa").contains("…");
        assertThat(out.replace(ESC + "[0m", "")).hasSize(11); // 10 'a' + '…'
    }

    @Test
    void ansiEscapesDoNotCountAsColumns() {
        // "abc" styled + a trailing "def"; with a 6-column budget all 6 visible chars survive.
        String styled = ESC + "[1mabc" + ESC + "[0mdef";

        String out = InlineSelector.truncateToWidth(styled, 6);

        assertThat(out).contains("abc").contains("def").doesNotContain("…");
    }

    @Test
    void truncationAppendsResetToAvoidColorBleed() {
        String styled = ESC + "[31m" + "x".repeat(50); // red run, never closed

        String out = InlineSelector.truncateToWidth(styled, 5);

        assertThat(out).endsWith(ESC + "[0m");
        assertThat(out).contains("…");
    }

    @Test
    void neverSplitsASurrogatePair() {
        String emoji = "🙂".repeat(20); // each 🙂 is a surrogate pair (2 chars, 1 code point)

        String out = InlineSelector.truncateToWidth(emoji, 5);

        assertThat(out).doesNotContain("�"); // no replacement char from a broken pair
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1)))
                        .as("high surrogate at %d is paired", i).isTrue();
            }
        }
    }

    @Test
    void nonPositiveWidthOrNull_yieldsEmpty() {
        assertThat(InlineSelector.truncateToWidth("abc", 0)).isEmpty();
        assertThat(InlineSelector.truncateToWidth(null, 10)).isEmpty();
    }
}
