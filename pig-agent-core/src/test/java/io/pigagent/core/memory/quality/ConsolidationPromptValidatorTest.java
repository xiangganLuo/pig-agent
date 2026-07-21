package io.pigagent.core.memory.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive offline check of the {@code %d}-placeholder contract the native {@code MemoryConsolidator}
 * imposes on a custom consolidation prompt (exactly two {@code %d}, no other unescaped {@code %}), plus
 * the non-blank rule for a custom flush prompt.
 */
class ConsolidationPromptValidatorTest {

    @Test
    void consolidationPromptWithExactlyTwoPercentD_isValid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt(
                "Merge into MEMORY.md within %d tokens (approximately %d characters). Output markdown."))
                .isTrue();
    }

    @Test
    void bareTwoPercentD_isValid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("%d and %d")).isTrue();
    }

    @Test
    void escapedPercentAlongsideTwoPercentD_isValid() {
        // "%%" is a literal percent — allowed; the two real conversions are still exactly two %d.
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt(
                "Trim to 50%% headroom: %d tokens (~%d chars)")).isTrue();
    }

    @Test
    void onePercentD_isInvalid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("Keep under %d tokens")).isFalse();
    }

    @Test
    void zeroPercentD_isInvalid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("Just merge, no budget")).isFalse();
    }

    @Test
    void threePercentD_isInvalid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("%d %d %d")).isFalse();
    }

    @Test
    void otherConversionAlongsideTwoPercentD_isInvalid() {
        // A stray %s (or any non-%d conversion) would break String.format(prompt, int, int).
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("%d tokens %d chars for %s"))
                .isFalse();
    }

    @Test
    void barePercentSign_isInvalid() {
        // "100%" would throw UnknownFormatConversionException at consolidation time.
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt(
                "Trim to 100% headroom: %d tokens %d chars")).isFalse();
    }

    @Test
    void danglingPercentAtEnd_isInvalid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("%d tokens %d chars %")).isFalse();
    }

    @Test
    void nullOrBlankConsolidationPrompt_isInvalid() {
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt(null)).isFalse();
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("")).isFalse();
        assertThat(ConsolidationPromptValidator.isValidConsolidationPrompt("   ")).isFalse();
    }

    @Test
    void flushPrompt_onlyRequiresNonBlank() {
        assertThat(ConsolidationPromptValidator.isValidFlushPrompt("Extract durable facts as bullets."))
                .isTrue();
        // A flush prompt is never String.format-ed, so a bare % is harmless (still valid).
        assertThat(ConsolidationPromptValidator.isValidFlushPrompt("Extract facts, keep 100% signal"))
                .isTrue();
        assertThat(ConsolidationPromptValidator.isValidFlushPrompt(null)).isFalse();
        assertThat(ConsolidationPromptValidator.isValidFlushPrompt("  ")).isFalse();
    }
}
