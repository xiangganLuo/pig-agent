package io.pigagent.tool.deferred;

/**
 * Side-effect seam for revealing a deferred tool: activate the AgentScope tool group it lives in, so
 * the tool re-enters the model schema and becomes callable. Kept as a tiny functional interface so
 * {@code tool_search} can be unit-tested with a fake reveal (no live Toolkit).
 */
@FunctionalInterface
public interface DeferredToolReveal {

    /**
     * Reveal the named tool (activate its group).
     *
     * @return {@code true} if the tool was deferred and has now been revealed; {@code false} if it
     *         was not a known deferred tool.
     */
    boolean reveal(String toolName);
}
