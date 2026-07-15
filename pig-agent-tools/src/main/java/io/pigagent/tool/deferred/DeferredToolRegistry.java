package io.pigagent.tool.deferred;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Holds the metadata of the tools currently <em>deferred</em> (hidden from the model's initial
 * schema) and answers {@code tool_search} queries against them. Thread-safe: {@code tool_search}
 * runs during a reasoning turn while the registry may be mutated by reveals.
 *
 * <p>Search matches a query's keyword tokens against each deferred tool's keywords / name /
 * description and ranks by overlap. Once a tool is <em>revealed</em> it is removed from the
 * searchable set (it is already back in the schema, no need to rediscover it); revealing a tool
 * also reveals any sibling sharing its {@code groupName} (a whole group is activated at once).
 */
public final class DeferredToolRegistry {

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
     * Rank still-deferred tools by relevance to {@code query}; returns at most {@code limit}
     * (most relevant first). A blank query returns an empty list (the caller surfaces a hint).
     */
    public List<DeferredTool> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Set<String> qTokens = Keywords.tokenize(query);
        String qLower = query.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        synchronized (lock) {
            for (DeferredTool t : deferred.values()) {
                int s = score(t, qTokens, qLower);
                if (s > 0) {
                    scored.add(new Scored(t, s));
                }
            }
        }
        scored.sort(Comparator.comparingInt((Scored x) -> x.score).reversed()
                .thenComparing(x -> x.tool.name()));
        List<DeferredTool> out = new ArrayList<>();
        for (Scored x : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(x.tool);
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

    private static int score(DeferredTool t, Set<String> qTokens, String qLower) {
        int score = 0;
        String nameLower = t.name().toLowerCase(Locale.ROOT);
        // Strong signal: the whole query is a substring of the name (or vice-versa).
        if (nameLower.contains(qLower) || qLower.contains(nameLower)) {
            score += 5;
        }
        for (String q : qTokens) {
            if (t.keywords().contains(q)) {
                score += 3;
            } else if (nameLower.contains(q)) {
                score += 2;
            } else if (t.description() != null
                    && t.description().toLowerCase(Locale.ROOT).contains(q)) {
                score += 1;
            }
        }
        return score;
    }

    private record Scored(DeferredTool tool, int score) {
    }
}
