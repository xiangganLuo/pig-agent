package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;

/**
 * Strategy for scoring how important a message is to keep verbatim during compression.
 *
 * <p>Extracted as an interface so the heuristic can evolve or be swapped/mocked independently of the
 * compression orchestration. Higher scores mean "more worth keeping verbatim even if old" — e.g.
 * user decisions, corrections, and errors — while low scores (chatter/acknowledgements) are the
 * first to be summarized. The default implementation is {@link HeuristicImportanceScorer}.
 */
public interface ImportanceScorer {

    /** Score at/above which a message is treated as high-importance (kept verbatim). */
    int HIGH_IMPORTANCE = 5;

    /** Score a single message (null → 0). Larger = more important to preserve verbatim. */
    int score(Msg msg);

    /** Whether the message should be retained verbatim rather than summarized. */
    default boolean isHighImportance(Msg msg) {
        return score(msg) >= HIGH_IMPORTANCE;
    }
}
