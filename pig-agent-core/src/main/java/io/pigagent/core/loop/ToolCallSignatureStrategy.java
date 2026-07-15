package io.pigagent.core.loop;

import java.util.Map;

/**
 * Strategy (GoF) for folding a single tool call into a comparison <em>signature</em>. The signature
 * is the unit the {@link LoopDetector}'s sliding window counts: two calls that must count as "the
 * same repeated action" MUST produce equal signatures, and calls that represent genuine progress
 * MUST produce different ones.
 *
 * <p>This is the most volatile / specialization-prone part of loop detection (e.g. bucketing
 * {@code readFile} by line range), so it is isolated behind this interface. The window + threshold
 * logic in {@link LoopDetector} is agnostic to how signatures are computed; swap the strategy to
 * change the rules without touching the counter.
 */
@FunctionalInterface
public interface ToolCallSignatureStrategy {

    /**
     * @param toolName the tool being called (never null/blank in practice)
     * @param input    the tool arguments (may be null or empty)
     * @return a stable, non-null signature string; equal signatures ⇒ "same repeated action"
     */
    String signature(String toolName, Map<String, Object> input);
}
