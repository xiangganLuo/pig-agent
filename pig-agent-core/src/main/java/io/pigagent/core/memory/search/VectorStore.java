package io.pigagent.core.memory.search;

import java.util.List;

/**
 * Pluggable vector index seam for hybrid memory search — capability {@code hybrid-memory-search}.
 * The default {@link InMemoryVectorStore} is pure-Java brute-force cosine (offline, zero native deps);
 * a persistent backend can be swapped in without touching the ranker.
 *
 * <p><b>Spike outcome (OD7).</b> The native vector extension {@code sqlite-vec} is not available offline
 * on this Windows/JDK setup, so the default is in-memory brute force — fine at personal-assistant
 * corpus scale (a handful of files, at most hundreds of chunks). Not the store's job to embed: it holds
 * and searches {@code float[]} vectors only.
 */
public interface VectorStore {

    /** Insert or replace the vector for {@code id}. */
    void upsert(String id, float[] vector);

    /** Remove all vectors (called on a full index rebuild). */
    void clear();

    /** Top-{@code k} ids by descending cosine similarity to {@code query} (fewer if the store is smaller). */
    List<Scored> search(float[] query, int k);

    /** A scored id (cosine similarity in {@code [-1, 1]}). */
    record Scored(String id, double score) {
    }
}
