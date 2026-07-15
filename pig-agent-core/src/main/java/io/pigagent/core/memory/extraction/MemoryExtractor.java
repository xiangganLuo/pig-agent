package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * Strategy for turning a completed conversation turn into classified {@link ExtractedFact}s.
 *
 * <p>This interface is the <b>mockable seam</b>: production wires {@link LlmMemoryExtractor} (which
 * asks the current model), while tests inject a fake returning fixed facts (or throwing) — so the
 * extraction pipeline can be exercised fully offline with no model. Implementations MUST be
 * best-effort: any failure should surface as an <em>empty</em> list (logged), never a thrown
 * exception, so a bad extraction can never break the turn (graceful degradation).
 */
@FunctionalInterface
public interface MemoryExtractor {

    /** An extractor that never extracts anything (useful as a null-object / test default). */
    MemoryExtractor NONE = turn -> List.of();

    /**
     * Extract durable, classified facts from the given (already noise-filtered) turn messages.
     *
     * @param turn user + assistant messages of the recent turn (tool noise stripped upstream)
     * @return zero or more facts; empty on nothing-to-extract or on failure
     */
    List<ExtractedFact> extract(List<Msg> turn);
}
