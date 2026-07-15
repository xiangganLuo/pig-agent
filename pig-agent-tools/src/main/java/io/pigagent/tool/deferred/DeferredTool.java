package io.pigagent.tool.deferred;

import java.util.Set;

/**
 * Immutable metadata for a tool that has been <em>deferred</em> — registered in the {@code Toolkit}
 * but hidden from the model's initial schema (parked in an inactive tool group). It carries just
 * enough for {@code tool_search} to describe the tool to the model and to reveal it on demand.
 *
 * @param name        the tool's name (as the model would call it)
 * @param description the tool's human/model-facing description
 * @param keywords    lowercase search keywords derived from name + description ({@link Keywords})
 * @param groupName   the AgentScope tool-group this tool lives in; revealing = activating this group
 */
public record DeferredTool(String name, String description, Set<String> keywords, String groupName) {

    public DeferredTool {
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
    }
}
