package io.pigagent.core.search;

/**
 * The minimal contract for a document the shared retrieval primitives can index and rank — capability
 * {@code shared-retrieval}. Each retrieval line — memory retrieval, tool search (Tool-OS), skill
 * matching (Skills) — supplies its own implementation, so all three consume one ranker
 * ({@link Bm25Index} + {@link HybridRanker}) and never drift apart in scoring.
 *
 * <p>Deliberately only two accessors: {@code id()} (a globally-unique identifier the ranked results are
 * keyed by) and {@code text()} (the body that participates in scoring). Domain-specific fields (e.g. a
 * memory document's source label) stay on the implementing type, not on this shared contract.
 */
public interface SearchDocument {

    /** A stable, globally-unique document id; ranked results are keyed by it. */
    String id();

    /** The document body that participates in keyword/vector scoring (never {@code null}). */
    String text();
}
