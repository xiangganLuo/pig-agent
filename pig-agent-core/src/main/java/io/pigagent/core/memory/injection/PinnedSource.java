package io.pigagent.core.memory.injection;

import java.util.Locale;

/**
 * Strategy selector for the pinned-core source (capability {@code memory-retrieval-injection}). The
 * pinned core is a stable, query-independent subset of {@code MEMORY.md} that stays in the (cached)
 * system prompt.
 */
public enum PinnedSource {

    /** Pin the section under a configured Markdown heading in {@code MEMORY.md}; absent → empty pinned. */
    HEADING,

    /** Pin the leading N characters of {@code MEMORY.md} (fallback that always yields content). */
    HEAD;

    /** Parse a config token (case-insensitive); unknown/blank → {@link #HEADING}. */
    public static PinnedSource fromConfig(String token) {
        if (token == null) {
            return HEADING;
        }
        return switch (token.strip().toLowerCase(Locale.ROOT)) {
            case "head" -> HEAD;
            default -> HEADING;
        };
    }
}
