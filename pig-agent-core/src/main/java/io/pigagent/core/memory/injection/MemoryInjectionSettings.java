package io.pigagent.core.memory.injection;

/**
 * Immutable settings for RAG-style memory injection — capability {@code memory-retrieval-injection}
 * (mirrors {@code MemorySearchConfig}: a config-backed value object the wiring layer builds from
 * {@code memory.injection}). All inputs are clamped/normalized in the compact constructor.
 *
 * <p>When {@link #enabled()} is {@code false} the {@code NativeMemoryContextMiddleware} keeps today's
 * behavior — the whole {@code MEMORY.md} is injected into the system prompt and there is no query-aware
 * injection — byte-identical to before this capability. When {@code true}, injection is split: a
 * pinned core stays in the (cached) system prompt and query-aware facts are injected into a trailing
 * ephemeral message (the prefix-cache-preserving split).
 *
 * @param enabled        split injection on (pinned + query-aware ephemeral); {@code false} = today's behavior
 * @param topK           max query-aware facts retrieved &amp; injected per turn (clamped &ge; 1)
 * @param pinnedSource   how the pinned core is selected from {@code MEMORY.md} (heading section or head)
 * @param pinnedHeading  heading name whose section is pinned when {@code pinnedSource == HEADING}
 * @param pinnedMaxChars cap on the pinned block injected into the system prompt (clamped &ge; 0; 0 = none)
 */
public record MemoryInjectionSettings(boolean enabled, int topK, PinnedSource pinnedSource,
                                      String pinnedHeading, int pinnedMaxChars) {

    public MemoryInjectionSettings {
        topK = Math.max(1, topK);
        pinnedSource = pinnedSource == null ? PinnedSource.HEADING : pinnedSource;
        pinnedHeading = (pinnedHeading == null || pinnedHeading.isBlank()) ? "Pinned" : pinnedHeading.strip();
        pinnedMaxChars = Math.max(0, pinnedMaxChars);
    }

    /** Default-safe settings: disabled (today's whole-file behavior), top-6, heading "Pinned", 800 chars. */
    public static MemoryInjectionSettings defaults() {
        return new MemoryInjectionSettings(false, 6, PinnedSource.HEADING, "Pinned", 800);
    }
}
