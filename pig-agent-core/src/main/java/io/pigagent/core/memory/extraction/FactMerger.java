package io.pigagent.core.memory.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure merge of newly-extracted facts into the existing set, <b>deduplicating and superseding by
 * subject</b> (never blindly appending).
 *
 * <p>Rules:
 * <ul>
 *   <li>Facts are keyed by {@link ExtractedFact#subjectKey()} (case-insensitive subject).</li>
 *   <li>An incoming fact <b>supersedes</b> any existing fact with the same subject — so a correction
 *       ("no, actually X") replaces the stale value rather than coexisting with it.</li>
 *   <li>When the incoming batch itself has multiple facts for one subject, the winner is chosen by
 *       {@link #prefer(ExtractedFact, ExtractedFact)}: a correction beats a non-correction, else the
 *       higher confidence wins, else the later one.</li>
 *   <li>Existing insertion order is preserved for untouched subjects; a superseded subject keeps its
 *       original position (updated in place); genuinely new subjects are appended.</li>
 * </ul>
 */
public final class FactMerger {

    /** Merge {@code incoming} into {@code existing}, deduped/superseded by subject. */
    public List<ExtractedFact> merge(List<ExtractedFact> existing, List<ExtractedFact> incoming) {
        Map<String, ExtractedFact> bySubject = new LinkedHashMap<>();
        if (existing != null) {
            for (ExtractedFact fact : existing) {
                if (fact != null && !fact.isBlank()) {
                    bySubject.put(fact.subjectKey(), fact);
                }
            }
        }
        if (incoming != null) {
            for (ExtractedFact fact : incoming) {
                if (fact == null || fact.isBlank()) {
                    continue;
                }
                String key = fact.subjectKey();
                ExtractedFact prior = bySubject.get(key);
                // Incoming supersedes existing; among incoming duplicates, prefer() picks the winner.
                bySubject.put(key, prior == null ? fact : preferIncoming(prior, fact));
            }
        }
        return new ArrayList<>(bySubject.values());
    }

    /**
     * When merging an incoming fact over a prior value already in the map, the incoming one always
     * wins if it is a correction; otherwise defer to {@link #prefer} so a lower-confidence duplicate
     * within the same batch does not overwrite a stronger one.
     */
    private ExtractedFact preferIncoming(ExtractedFact prior, ExtractedFact incoming) {
        return prefer(prior, incoming);
    }

    /**
     * Pick the stronger of two same-subject facts: correction &gt; non-correction; then higher
     * confidence; ties go to {@code b} (the later one).
     */
    ExtractedFact prefer(ExtractedFact a, ExtractedFact b) {
        if (b.correction() && !a.correction()) {
            return b;
        }
        if (a.correction() && !b.correction()) {
            return a;
        }
        return b.confidence() >= a.confidence() ? b : a;
    }
}
