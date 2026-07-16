package io.pigagent.tool.deferred;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.util.List;

/**
 * The {@code tool_search} built-in ({@code READ_ONLY}). Borrowed from deerflow's
 * {@code DeferredToolFilterMiddleware}: large/rarely-used tools are kept OUT of the model's initial
 * tool schema (saving prompt tokens) and discovered on demand here. Given a query, it returns the
 * matching deferred tools' names + descriptions and <em>reveals</em> them (activates their group) so
 * they are callable in subsequent steps.
 *
 * <p>Contract-compliant: on any internal failure it returns the canonical {@code {"error":...}}
 * (via {@link ToolErrors}) rather than throwing.
 */
public final class ToolSearchTool {

    /** Cap on how many matches a single search reveals/returns, to keep the reply and schema bounded. */
    static final int MAX_RESULTS = 8;
    /** Cap on how many available names to hint when nothing matched. */
    static final int MAX_HINTS = 20;

    private final DeferredToolRegistry registry;
    private final DeferredToolReveal reveal;

    public ToolSearchTool(DeferredToolRegistry registry, DeferredToolReveal reveal) {
        this.registry = registry;
        this.reveal = reveal;
    }

    @Tool(name = "tool_search", description = "Discover additional tools that exist but are not "
            + "currently loaded into your tool list (they were deferred to save context). Given a "
            + "query of keywords describing the capability you need, this returns matching tools' "
            + "names and descriptions and makes them available to call in your next steps. Use it "
            + "whenever you need a capability you do not see among your current tools.",
            readOnly = true)
    public String toolSearch(@ToolParam(name = "query", description = "Keywords describing the "
            + "capability you need, e.g. 'weather forecast', 'query database', 'send email'.")
                             String query) {
        try {
            if (registry == null || registry.isEmpty()) {
                return "No additional tools are available to search.";
            }
            List<DeferredTool> matches = registry.search(query, MAX_RESULTS);
            if (matches.isEmpty()) {
                return noMatchHint(query);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" tool(s) matching \"")
                    .append(safe(query)).append("\" (now available to call):\n");
            for (DeferredTool t : matches) {
                if (reveal != null) {
                    reveal.reveal(t.name());
                }
                sb.append("- ").append(t.name()).append(": ")
                        .append(t.description() == null ? "" : t.description().strip()).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (RuntimeException e) {
            return ToolErrors.message("tool_search failed: " + e.getMessage());
        }
    }

    private String noMatchHint(String query) {
        List<String> names = registry.deferredNames();
        if (names.isEmpty()) {
            return "No tools matched \"" + safe(query) + "\" (no more tools to discover).";
        }
        List<String> shown = names.size() > MAX_HINTS ? names.subList(0, MAX_HINTS) : names;
        return "No tools matched \"" + safe(query) + "\". Tools you can search for: "
                + String.join(", ", shown)
                + ". Try tool_search again with different keywords.";
    }

    private static String safe(String s) {
        return s == null ? "" : s.strip();
    }
}
