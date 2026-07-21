package io.pigagent.tool.skills;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.core.search.Bm25Index;
import io.pigagent.core.search.HybridRanker;
import io.pigagent.tool.contract.ToolErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code skill_search} built-in ({@code READ_ONLY}, capability {@code skill-matching}, Skills S2).
 * The independent, task-semantic sibling of {@code listSkills}: given a query describing what the model
 * is trying to do, it returns the top matching skills (name + description) ranked by relevance so the
 * model can pick + {@code loadSkill} one instead of scanning the whole flat list.
 *
 * <p>Ranking reuses the <b>shared kernel retrieval primitives</b> ({@code io.pigagent.core.search}:
 * {@link Bm25Index} + {@link HybridRanker} + CJK tokenizer) — the same ranker {@code memory_search}
 * and {@code tool_search} use, so the three retrieval lines never drift in scoring (no bespoke keyword
 * count here). Each skill is projected onto a {@link SkillDocument} ({@code id}=name,
 * {@code text}=name+description+keywords, built from the cheap {@link Skill#metadata()} — never the
 * body); BM25 scores are routed through {@link HybridRanker} with an empty vector map (BM25-only
 * degradation, the memory line's fusion exit).
 *
 * <p>Shares the {@link SkillRegistry} of the {@code SkillsTool} (via {@code SkillsTool.registry()}) so
 * it ranks the exact same skill set {@code listSkills} lists. A blank query, or a query with no
 * in-vocabulary match, falls back to the full flat listing (never an error, never empty) so skills are
 * always discoverable. Contract-compliant: any internal failure returns the canonical
 * {@code {"error":...}} (via {@link ToolErrors}) rather than throwing.
 */
public final class SkillSearchTool {

    private static final Logger log = LoggerFactory.getLogger(SkillSearchTool.class);

    /** Default cap on returned matches when config supplies none (mirrors a small, bounded reply). */
    static final int DEFAULT_TOP_K = 10;
    /** BM25-only: positive weight preserves the normalized BM25 ordering; vector weight is 0. */
    private static final double BM25_WEIGHT = 1.0;

    private final SkillRegistry registry;
    private final int topK;
    private final double minScore;

    public SkillSearchTool(SkillRegistry registry) {
        this(registry, DEFAULT_TOP_K, 0.0);
    }

    public SkillSearchTool(SkillRegistry registry, int topK, double minScore) {
        this.registry = registry;
        this.topK = topK > 0 ? topK : DEFAULT_TOP_K;
        this.minScore = Math.max(0.0, minScore);
    }

    @Tool(name = "skill_search", description = "Find the most relevant skills for the current task by "
            + "semantic keyword match, instead of listing every skill. Given a query describing what "
            + "you are trying to do, this returns the top matching skills (name + description) ranked by "
            + "relevance; then load one with loadSkill. With a blank query or no match it lists all "
            + "skills.", readOnly = true)
    public String skillSearch(@ToolParam(name = "query", description = "Keywords describing the task you "
            + "need a skill for, e.g. 'review code for security', 'debug a crash', 'write tests'.")
                              String query) {
        try {
            List<Skill> all = registry.all();
            if (all.isEmpty()) {
                return "No skills found.";
            }
            List<Skill> matches = rank(all, query);
            List<Skill> result = matches.isEmpty() ? sortedByName(all) : matches;
            return result.stream().map(this::formatListing).collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return ToolErrors.message("skill_search failed: " + e.getMessage());
        }
    }

    /**
     * Rank the skills by relevance to {@code query} via the shared ranker; empty when the query is
     * blank or has no in-vocabulary match (the caller then falls back to the flat listing).
     */
    private List<Skill> rank(List<Skill> all, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<SkillDocument> docs = new ArrayList<>(all.size());
        Map<String, Skill> byId = new HashMap<>();
        for (Skill s : all) {
            docs.add(SkillDocument.of(s.name(), safeMetadata(s))); // metadata only, never the body
            byId.put(s.name(), s);
        }
        Bm25Index index = new Bm25Index();
        index.index(docs);
        Map<String, Double> bm25 = index.score(query);
        if (bm25.isEmpty()) {
            return List.of();
        }
        List<HybridRanker.Scored> ranked =
                HybridRanker.rank(bm25, Map.of(), BM25_WEIGHT, 0.0, minScore, topK);
        List<Skill> out = new ArrayList<>(ranked.size());
        for (HybridRanker.Scored s : ranked) {
            Skill sk = byId.get(s.id());
            if (sk != null) {
                out.add(sk);
            }
        }
        return out;
    }

    private static List<Skill> sortedByName(List<Skill> all) {
        return all.stream().sorted(Comparator.comparing(Skill::name)).collect(Collectors.toList());
    }

    /** Format one listing line, mirroring {@code SkillsTool.formatListing} (metadata only). */
    private String formatListing(Skill skill) {
        SkillMetadata meta = safeMetadata(skill);
        return meta.hasDescription() ? "- " + skill.name() + " — " + meta.description() : "- " + skill.name();
    }

    private SkillMetadata safeMetadata(Skill skill) {
        try {
            SkillMetadata m = skill.metadata();
            return m != null ? m : SkillMetadata.ofName(skill.name());
        } catch (RuntimeException e) {
            log.warn("Failed to read metadata for skill '{}': {}", skill.name(), e.toString());
            return SkillMetadata.ofName(skill.name());
        }
    }
}
