package io.pigagent.tool.deferred;

import io.pigagent.core.search.Bm25Index;
import io.pigagent.core.search.HybridRanker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the metadata of the tools currently <em>deferred</em> (hidden from the model's initial
 * schema) and answers {@code tool_search} queries against them. Thread-safe: {@code tool_search}
 * runs during a reasoning turn while the registry may be mutated by reveals.
 *
 * <p>Search ranks the still-deferred tools with the <b>shared kernel retrieval primitives</b>
 * ({@code io.pigagent.core.search}: {@link Bm25Index} + {@link HybridRanker} + CJK tokenizer) — the
 * same ranker {@code memory_search} uses — so tool search and memory search never drift apart in
 * scoring (no bespoke keyword count here). Each deferred tool is projected onto a {@link ToolDocument}
 * ({@code id}=name, {@code text}=name+description+keywords); BM25 scores are routed through
 * {@link HybridRanker} with an empty vector map (BM25-only degradation, the memory line's fusion exit).
 * Once a tool is <em>revealed</em> it is removed from the searchable set (it is already back in the
 * schema, no need to rediscover it); revealing a tool also reveals any sibling sharing its
 * {@code groupName} (a whole group is activated at once).
 */
public final class DeferredToolRegistry {

    /**
     * Weight on the (normalized) BM25 component when routing through {@link HybridRanker}. Tool search
     * is BM25-only (no embeddings — tool metadata is short and few; see design D2), so the vector
     * weight is 0 and the vector map is empty; this weight only needs to be positive to preserve the
     * normalized BM25 ordering.
     */
    private static final double BM25_WEIGHT = 1.0;

    private final Map<String, DeferredTool> deferred = new LinkedHashMap<>();
    private final Map<String, DeferredTool> revealed = new LinkedHashMap<>();
    private final Object lock = new Object();

    /** Register a deferred tool's metadata. A duplicate name replaces the earlier entry. */
    public void add(DeferredTool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return;
        }
        synchronized (lock) {
            deferred.put(tool.name(), tool);
        }
    }

    /**
     * Rank still-deferred tools by relevance to {@code query} using the shared BM25 ranker; returns at
     * most {@code limit} (most relevant first). A blank query, empty registry, or no in-vocabulary
     * match returns an empty list (the caller surfaces a hint).
     */
    public List<DeferredTool> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        List<DeferredTool> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(deferred.values());
        }
        if (snapshot.isEmpty()) {
            return List.of();
        }
        List<ToolDocument> docs = new ArrayList<>(snapshot.size());
        Map<String, DeferredTool> byId = new HashMap<>();
        for (DeferredTool t : snapshot) {
            docs.add(ToolDocument.of(t));
            byId.put(t.name(), t);
        }
        Bm25Index index = new Bm25Index();
        index.index(docs);
        Map<String, Double> bm25 = index.score(query);
        if (bm25.isEmpty()) {
            return List.of();
        }
        List<HybridRanker.Scored> ranked =
                HybridRanker.rank(bm25, Map.of(), BM25_WEIGHT, 0.0, 0.0, limit);
        List<DeferredTool> out = new ArrayList<>(ranked.size());
        for (HybridRanker.Scored s : ranked) {
            DeferredTool t = byId.get(s.id());
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** A still-deferred tool by exact name, if present. */
    public Optional<DeferredTool> find(String name) {
        synchronized (lock) {
            return Optional.ofNullable(deferred.get(name));
        }
    }

    /**
     * Mark {@code name} (and any sibling sharing its group) as revealed: move them out of the
     * searchable deferred set. Returns the tools that transitioned (empty if {@code name} was not
     * deferred / already revealed).
     */
    public List<DeferredTool> markRevealed(String name) {
        synchronized (lock) {
            DeferredTool target = deferred.get(name);
            if (target == null) {
                return List.of();
            }
            String group = target.groupName();
            List<DeferredTool> moved = new ArrayList<>();
            for (DeferredTool t : new ArrayList<>(deferred.values())) {
                if (t.name().equals(name) || (group != null && group.equals(t.groupName()))) {
                    deferred.remove(t.name());
                    revealed.put(t.name(), t);
                    moved.add(t);
                }
            }
            return moved;
        }
    }

    /** All tool names still deferred (searchable). Order-stable snapshot. */
    public List<String> deferredNames() {
        synchronized (lock) {
            return new ArrayList<>(deferred.keySet());
        }
    }

    /** All still-deferred tools. Order-stable snapshot. */
    public List<DeferredTool> all() {
        synchronized (lock) {
            return new ArrayList<>(deferred.values());
        }
    }

    /** Tool names already revealed via {@code tool_search}. Order-stable snapshot. */
    public List<String> revealedNames() {
        synchronized (lock) {
            return new ArrayList<>(revealed.keySet());
        }
    }

    /** True when nothing is deferred (nor ever revealed) — the feature effectively contributes nothing. */
    public boolean isEmpty() {
        synchronized (lock) {
            return deferred.isEmpty() && revealed.isEmpty();
        }
    }
}
