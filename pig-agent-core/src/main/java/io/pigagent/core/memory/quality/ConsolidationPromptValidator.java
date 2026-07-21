package io.pigagent.core.memory.quality;

/**
 * Pure, offline placeholder validator for custom memory prompts — capability
 * {@code memory-consolidation-quality}. AgentScope 2.0's native {@code MemoryConsolidator} consumes the
 * consolidation prompt via {@code String.format(consolidationPrompt, maxTokens, maxChars)} (the default
 * {@code MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT} carries exactly two {@code %d} — "within %d
 * tokens (approximately %d characters)"). A custom consolidation prompt therefore MUST contain
 * <b>exactly two {@code %d}</b> conversions and <b>no other unescaped {@code %}</b>, or the background
 * (async) consolidation would throw {@code MissingFormatArgumentException} /
 * {@code UnknownFormatConversionException} at {@code String.format} time.
 *
 * <p>The native {@code MemoryFlushManager} does <b>not</b> {@code String.format} the flush prompt (it is
 * a plain SYSTEM prompt), so a flush prompt has no placeholder requirement — only a non-blank check.
 *
 * <p>This validator is the pig-side fail-safe: the wiring layer calls it before handing a custom prompt
 * to {@code MemoryConfig.Builder} and falls back to the native default when it returns {@code false}, so
 * a malformed prompt can never reach — or crash — the background consolidation. Pure/deterministic.
 */
public final class ConsolidationPromptValidator {

    /** The exact number of {@code %d} placeholders the native consolidation {@code String.format} needs. */
    static final int REQUIRED_D_PLACEHOLDERS = 2;

    private ConsolidationPromptValidator() {
    }

    /**
     * A custom consolidation prompt is valid iff it is non-blank, contains exactly two {@code %d}
     * conversions, and contains no other {@code %}-initiated conversion (a literal percent must be
     * escaped as {@code %%}). Returns {@code false} for {@code null}/blank or any malformed placeholder
     * shape — the caller then falls back to the native default prompt.
     */
    public static boolean isValidConsolidationPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        int dCount = 0;
        int i = 0;
        int n = prompt.length();
        while (i < n) {
            char c = prompt.charAt(i);
            if (c != '%') {
                i++;
                continue;
            }
            if (i + 1 >= n) {
                return false; // dangling '%'
            }
            char next = prompt.charAt(i + 1);
            if (next == '%') {
                i += 2; // escaped literal percent
            } else if (next == 'd') {
                dCount++;
                i += 2;
            } else {
                return false; // any other conversion (%s, %f, "% ", %1$d, …) is rejected as unsafe
            }
        }
        return dCount == REQUIRED_D_PLACEHOLDERS;
    }

    /**
     * A custom flush prompt is valid iff it is non-blank — the native flush path never
     * {@code String.format}s it, so it carries no placeholder requirement.
     */
    public static boolean isValidFlushPrompt(String prompt) {
        return prompt != null && !prompt.isBlank();
    }
}
