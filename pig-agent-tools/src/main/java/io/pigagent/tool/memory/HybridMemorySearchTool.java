package io.pigagent.tool.memory;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.core.memory.search.MemoryDocument;
import io.pigagent.core.memory.search.MemorySearchIndex;
import io.pigagent.tool.contract.ToolErrors;

import java.util.List;
import java.util.Objects;

/**
 * The pig <b>hybrid</b> {@code memory_search} tool (capability {@code hybrid-memory-search}). It keeps
 * the native tool's {@code @Tool} name {@code memory_search} (so the model interface is unchanged) but
 * ranks results with hybrid BM25 + vector scoring over the memory corpus ({@code MEMORY.md} +
 * {@code memory/*.md} + {@code USER.md}) via {@link MemorySearchIndex}, instead of the native
 * keyword-only substring scan. Registered only when {@code memory.search.hybrid-enabled}; when it is,
 * {@code PigAgent.Builder} disables the native memory tools so this one supersedes them.
 *
 * <p>Classified {@code READ_ONLY} (the name is already READ_ONLY in {@code ToolRiskClassifier}). Return
 * contract: matching snippets on success; a friendly "no match" line when empty; the canonical
 * {@code {"error":"<reason>"}} on failure (never throws). Never echoes credentials.
 */
public final class HybridMemorySearchTool {

    static final String TOOL_NAME = "memory_search";

    private final MemorySearchIndex index;
    private final int defaultLimit;

    public HybridMemorySearchTool(MemorySearchIndex index, int defaultLimit) {
        this.index = Objects.requireNonNull(index, "index");
        this.defaultLimit = Math.max(1, defaultLimit);
    }

    @Tool(name = "memory_search", readOnly = true, description = "Search your long-term memory "
            + "(consolidated MEMORY.md, the daily memory ledger, and the user profile USER.md) for "
            + "information relevant to a query. Uses hybrid keyword + semantic ranking, so a paraphrase "
            + "of what was remembered can still match. Returns the most relevant memory snippets.")
    public String memorySearch(@ToolParam(name = "query", description = "What to look for in memory, "
            + "e.g. 'the user's name', 'preferred reply language', 'the deploy procedure we agreed on'.")
                               String query) {
        try {
            if (query == null || query.isBlank()) {
                return ToolErrors.message("memory_search: query must not be empty");
            }
            List<MemoryDocument> hits = index.search(query, defaultLimit);
            if (hits.isEmpty()) {
                return "No memory matched: " + query.strip();
            }
            StringBuilder sb = new StringBuilder();
            for (MemoryDocument hit : hits) {
                sb.append("## ").append(hit.sourceLabel()).append('\n')
                        .append(hit.text().strip()).append("\n\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return ToolErrors.message("memory_search failed: " + e.getClass().getSimpleName());
        }
    }
}
