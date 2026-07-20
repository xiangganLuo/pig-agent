package io.pigagent.core.memory.injection;

import io.pigagent.core.memory.search.MemoryDocument;

import java.util.List;

/**
 * The retrieval seam for query-aware memory injection (capability {@code memory-retrieval-injection}).
 * Deliberately narrow — only {@code (query, topK) → results} — so the injection middleware depends ONLY
 * on the <b>stable public facade</b> of {@code MemorySearchIndex.search(String, int)} (a natural
 * method-reference match, {@code index::search}), never its internals. This keeps the coordination
 * boundary with <b>R0 (shared-retrieval-primitive)</b> clean: R0 may refactor {@code MemorySearchIndex}
 * internally as long as this facade holds.
 *
 * <p>A {@code null} retriever disables query-aware injection (pinned-only). Implementations MUST NOT
 * throw for a normal query; the middleware also guards defensively.
 */
@FunctionalInterface
public interface MemoryRetriever {

    /** Retrieve at most {@code topK} relevant memory chunks for {@code query} (never {@code null}). */
    List<MemoryDocument> retrieve(String query, int topK);
}
