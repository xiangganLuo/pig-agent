package io.pigagent.core.memory.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default {@link VectorStore}: pure-Java brute-force cosine over stored {@code float[]} vectors
 * (capability {@code hybrid-memory-search}). Zero native dependencies (no {@code sqlite-vec}) so it is
 * offline-green; O(N·d) search is negligible at personal-assistant corpus scale. Not thread-safe on its
 * own — the owning {@link MemorySearchIndex} guards access under a lock.
 */
public final class InMemoryVectorStore implements VectorStore {

    private final Map<String, float[]> vectors = new LinkedHashMap<>();

    @Override
    public void upsert(String id, float[] vector) {
        if (id == null || vector == null) {
            return;
        }
        vectors.put(id, vector);
    }

    @Override
    public void clear() {
        vectors.clear();
    }

    @Override
    public List<Scored> search(float[] query, int k) {
        List<Scored> scored = new ArrayList<>(vectors.size());
        if (query == null || query.length == 0) {
            return scored;
        }
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            scored.add(new Scored(e.getKey(), Vectors.cosine(query, e.getValue())));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (k > 0 && scored.size() > k) {
            return new ArrayList<>(scored.subList(0, k));
        }
        return scored;
    }

    /** Number of stored vectors (test/inspection helper). */
    public int size() {
        return vectors.size();
    }
}
