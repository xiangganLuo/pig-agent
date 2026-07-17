package io.pigagent.core.memory.search;

import java.util.Objects;

/**
 * One indexed chunk of the memory corpus — capability {@code hybrid-memory-search}. A document is a
 * focused section of a source file ({@code MEMORY.md} / a daily ledger {@code memory/*.md} / the user
 * profile {@code USER.md}), so a search hit is a snippet rather than a whole file. Immutable.
 *
 * @param id          a stable unique id (source label + chunk ordinal)
 * @param sourceLabel a human-readable source label shown with a hit (e.g. {@code "MEMORY.md"})
 * @param text        the chunk body (never {@code null})
 */
public record MemoryDocument(String id, String sourceLabel, String text) {

    public MemoryDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        text = text == null ? "" : text;
    }
}
