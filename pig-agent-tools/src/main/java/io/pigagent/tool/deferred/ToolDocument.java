package io.pigagent.tool.deferred;

import io.pigagent.core.search.SearchDocument;

import java.util.Set;

/**
 * A deferred tool projected onto the shared retrieval contract {@link SearchDocument} — capability
 * {@code deferred-tools} (Tool-OS T2). This is the tool-search line's own {@code SearchDocument}
 * implementation, mirroring the memory line's {@code MemoryDocument}: it lets {@code tool_search}
 * rank via the same kernel ranker ({@code io.pigagent.core.search}: {@code Bm25Index} + {@code
 * HybridRanker} + CJK {@code Tokenizer}) as {@code memory_search}, so the two never drift apart in
 * scoring. Only depends on {@code io.pigagent.core.search}, never on the memory domain.
 *
 * @param id   the tool name (also how the model calls it and how ranked results are keyed back)
 * @param text the scored body: tool name + description + keywords (keywords carry the camelCase and
 *             CJK splits that the shared {@code Tokenizer} would not derive from the raw name alone,
 *             so e.g. a query {@code "search"} still matches a tool named {@code webSearch})
 */
record ToolDocument(String id, String text) implements SearchDocument {

    /** Build a document from a deferred tool: {@code id}=name, {@code text}=name + description + keywords. */
    static ToolDocument of(DeferredTool tool) {
        return new ToolDocument(tool.name(), buildText(tool.name(), tool.description(), tool.keywords()));
    }

    private static String buildText(String name, String description, Set<String> keywords) {
        StringBuilder sb = new StringBuilder();
        if (name != null) {
            sb.append(name);
        }
        if (description != null && !description.isBlank()) {
            sb.append(' ').append(description);
        }
        if (keywords != null) {
            for (String k : keywords) {
                if (k != null && !k.isBlank()) {
                    sb.append(' ').append(k);
                }
            }
        }
        return sb.toString();
    }
}
