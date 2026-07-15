package io.pigagent.core.memory.extraction;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure confidence gate: only facts the model is confident enough about are persisted.
 *
 * <p>A fact passes when its {@code confidence} is at least the threshold. <b>Corrections bypass the
 * threshold</b> — when the user corrects a prior fact, that signal is treated as high confidence
 * regardless of the raw score, and the fact is normalized via {@link ExtractedFact#asCorrection()}
 * so downstream merge always lets it supersede the stale fact. Blank facts are dropped.
 */
public final class ConfidenceGate {

    /**
     * Keep facts with {@code confidence >= threshold} (or any correction, normalized to high
     * confidence). Order is preserved; input is not mutated.
     */
    public List<ExtractedFact> gate(List<ExtractedFact> facts, double threshold) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<ExtractedFact> kept = new ArrayList<>();
        for (ExtractedFact fact : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            if (fact.correction()) {
                kept.add(fact.asCorrection()); // correction → high confidence, always kept
            } else if (fact.confidence() >= threshold) {
                kept.add(fact);
            }
        }
        return kept;
    }
}
