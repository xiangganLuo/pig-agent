package io.pigagent.core.memory.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates hybrid BM25 + vector memory search (capability {@code hybrid-memory-search}, the
 * Strategy that ties the parts together). Holds a {@link MemoryCorpusLoader}, a {@link Bm25Index}, a
 * pluggable {@link VectorStore}, and an optional {@link Embedder} (null ⇒ BM25-only). Index build is
 * <b>lazy and incremental</b>: built on first query, rebuilt when the corpus fingerprint changes and at
 * least {@code rebuildThrottleMillis} have elapsed. {@code search} scores BM25 over all docs, cosine
 * over the vector store (when an embedder is present), blends via {@link HybridRanker}, and maps the
 * ranked ids back to {@link MemoryDocument}s.
 *
 * <p><b>Fault-tolerant / graceful degradation.</b> A missing corpus file is skipped by the loader; an
 * embedder failure (e.g. a live network error) is caught and the affected build/query falls back to
 * BM25-only — search never throws for a query, returning an empty list at worst.
 */
public final class MemorySearchIndex {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchIndex.class);

    private final MemoryCorpusLoader loader;
    private final VectorStore vectorStore;
    private final Embedder embedder; // nullable → BM25-only
    private final MemorySearchConfig config;

    private final Bm25Index bm25 = new Bm25Index();
    private final Object lock = new Object();

    private Map<String, MemoryDocument> docsById = new LinkedHashMap<>();
    private boolean built = false;
    private long lastFingerprint = Long.MIN_VALUE;
    private long lastBuildAtMillis = 0L;

    public MemorySearchIndex(MemoryCorpusLoader loader, VectorStore vectorStore,
                             Embedder embedder, MemorySearchConfig config) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.embedder = embedder;
        this.config = config == null ? MemorySearchConfig.defaults() : config;
    }

    /** Whether a real vector layer is active (an embedder is wired). */
    public boolean vectorEnabled() {
        return embedder != null;
    }

    /**
     * Hybrid-search the memory corpus for {@code query}, returning at most {@code topK} document
     * snippets (≤0 ⇒ the configured default). Empty for a blank query or an empty corpus; never throws.
     */
    public List<MemoryDocument> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK > 0 ? topK : config.topK();
        synchronized (lock) {
            ensureBuilt();
            Map<String, Double> bmScores = bm25.score(query);
            Map<String, Double> vecScores = vectorScores(query, k);
            List<HybridRanker.Scored> ranked = HybridRanker.rank(bmScores, vecScores,
                    config.bm25Weight(), config.vectorWeight(), config.minScore(), k);
            List<MemoryDocument> out = new ArrayList<>(ranked.size());
            for (HybridRanker.Scored s : ranked) {
                MemoryDocument doc = docsById.get(s.id());
                if (doc != null) {
                    out.add(doc);
                }
            }
            return out;
        }
    }

    /** Force a rebuild on next query (e.g. after an external corpus edit); test/ops helper. */
    public void invalidate() {
        synchronized (lock) {
            built = false;
        }
    }

    /** Cosine scores for the query vector against the store; empty when no embedder or on failure. */
    private Map<String, Double> vectorScores(String query, int k) {
        if (embedder == null) {
            return Map.of();
        }
        float[] qVec;
        try {
            qVec = embedder.embed(query);
        } catch (RuntimeException e) {
            log.debug("Query embedding failed — falling back to BM25-only for this query: {}", e.getMessage());
            return Map.of();
        }
        int candidates = Math.max(k, k * config.candidateMultiplier());
        Map<String, Double> scores = new HashMap<>();
        for (VectorStore.Scored s : vectorStore.search(qVec, candidates)) {
            scores.put(s.id(), s.score());
        }
        return scores;
    }

    /** Build lazily; rebuild when the corpus changed and the throttle window has elapsed. */
    private void ensureBuilt() {
        long fingerprint = loader.fingerprint();
        long now = System.currentTimeMillis();
        boolean stale = fingerprint != lastFingerprint
                && (now - lastBuildAtMillis) >= config.rebuildThrottleMillis();
        if (built && !stale) {
            return;
        }
        rebuild(fingerprint, now);
    }

    private void rebuild(long fingerprint, long now) {
        List<MemoryDocument> docs = loader.load();
        bm25.index(docs);
        vectorStore.clear();
        Map<String, MemoryDocument> byId = new LinkedHashMap<>();
        for (MemoryDocument doc : docs) {
            byId.put(doc.id(), doc);
        }
        docsById = byId;
        if (embedder != null) {
            embedCorpus(docs);
        }
        built = true;
        lastFingerprint = fingerprint;
        lastBuildAtMillis = now;
        log.debug("Memory search index built: {} docs, vector={}", docs.size(), embedder != null);
    }

    /** Embed every doc into the store; a failure degrades the whole build to BM25-only (store cleared). */
    private void embedCorpus(List<MemoryDocument> docs) {
        try {
            for (MemoryDocument doc : docs) {
                vectorStore.upsert(doc.id(), embedder.embed(doc.text()));
            }
        } catch (RuntimeException e) {
            log.warn("Corpus embedding failed — this build is BM25-only: {}", e.getMessage());
            vectorStore.clear();
        }
    }
}
