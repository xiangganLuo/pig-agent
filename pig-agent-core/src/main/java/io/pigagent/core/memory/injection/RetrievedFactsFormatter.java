package io.pigagent.core.memory.injection;

import io.pigagent.core.memory.search.MemoryDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure formatting of query-aware retrieved facts into a single trailing ephemeral message body
 * (capability {@code memory-retrieval-injection}), plus dedup against the pinned block so a fact
 * already pinned in the system prompt is not injected twice (design D5). No model call; never throws.
 */
public final class RetrievedFactsFormatter {

    /** Header of the injected block (a trailing user-side message; never part of the system prompt). */
    static final String HEADER = "## Relevant memory (retrieved for this message)";

    private RetrievedFactsFormatter() {
    }

    /**
     * Drop hits whose (whitespace-normalized) text is already contained in {@code pinnedBlock}, so the
     * same fact is not injected both in the system prompt (pinned) and the trailing message.
     */
    public static List<MemoryDocument> dedupAgainstPinned(List<MemoryDocument> hits, String pinnedBlock) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        String pinnedNorm = normalize(pinnedBlock);
        List<MemoryDocument> out = new ArrayList<>(hits.size());
        for (MemoryDocument d : hits) {
            if (d == null) {
                continue;
            }
            String t = normalize(d.text());
            if (!pinnedNorm.isEmpty() && !t.isEmpty() && pinnedNorm.contains(t)) {
                continue; // already pinned in the system prompt
            }
            out.add(d);
        }
        return out;
    }

    /** Format hits as a bounded bullet list; {@code ""} when there is nothing meaningful to inject. */
    public static String format(List<MemoryDocument> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (MemoryDocument d : hits) {
            if (d == null) {
                continue;
            }
            String text = normalize(d.text());
            if (!text.isEmpty()) {
                lines.add("- " + text);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return HEADER + "\n" + String.join("\n", lines);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }
}
