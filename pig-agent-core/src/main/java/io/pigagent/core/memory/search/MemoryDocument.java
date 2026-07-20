package io.pigagent.core.memory.search;

import io.pigagent.core.search.SearchDocument;

import java.util.Objects;

/**
 * One indexed chunk of the memory corpus — capability {@code hybrid-memory-search}. A document is a
 * focused section of a source file ({@code MEMORY.md} / a daily ledger {@code memory/*.md} / the user
 * profile {@code USER.md}), so a search hit is a snippet rather than a whole file. Immutable.
 *
 * <p>Implements the shared {@link SearchDocument} contract ({@code id()} + {@code text()}) so the memory
 * corpus indexes through the same {@link io.pigagent.core.search.Bm25Index} the tool/skill retrieval
 * lines use; the memory-specific {@code sourceLabel} stays here, off the shared contract.
 *
 * @param id          a stable unique id (source label + chunk ordinal)
 * @param sourceLabel a human-readable source label shown with a hit (e.g. {@code "MEMORY.md"})
 * @param text        the chunk body (never {@code null})
 */
public record MemoryDocument(String id, String sourceLabel, String text) implements SearchDocument {

    public MemoryDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        text = text == null ? "" : text;
    }
}
