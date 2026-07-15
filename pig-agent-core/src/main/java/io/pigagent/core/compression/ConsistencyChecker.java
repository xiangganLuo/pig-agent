package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight post-compression consistency check: verifies that every message that MUST survive
 * verbatim (pinned / high-importance turns, plus protected verbatim spans) still appears in the
 * compression candidate. It is a defense-in-depth net (mirroring the tool-contract guard): if a
 * required fact was accidentally swallowed by the summarizer, the check fails so the orchestrator
 * can fall back to safer behavior rather than silently drop critical context.
 *
 * <p>Matching is a whitespace-normalized substring test — deliberately structural, aimed at
 * catching hard "the whole thing got summarized away" losses, not semantic drift.
 */
public final class ConsistencyChecker {

    /** Result of a check: whether all required content survived, and what was missing if not. */
    public record Result(boolean ok, List<String> missing) {
    }

    private final VerbatimGuard guard;

    public ConsistencyChecker(VerbatimGuard guard) {
        this.guard = guard;
    }

    /**
     * Verify each {@code required} message (or its protected spans) survives in {@code candidate}.
     * A required message with protected spans is satisfied when all its spans survive; otherwise the
     * whole rendered message must survive.
     */
    public Result check(List<Msg> required, List<Msg> candidate) {
        String haystack = normalize(renderAll(candidate));
        List<String> missing = new ArrayList<>();
        if (required != null) {
            for (Msg req : required) {
                String text = MsgContentRenderer.render(req, 0);
                if (text == null || text.isBlank()) {
                    continue;
                }
                List<String> spans = guard.protectedSpans(text);
                if (spans.isEmpty()) {
                    if (!haystack.contains(normalize(text))) {
                        missing.add(text);
                    }
                } else {
                    for (String span : spans) {
                        if (!haystack.contains(normalize(span))) {
                            missing.add(span);
                        }
                    }
                }
            }
        }
        return new Result(missing.isEmpty(), missing);
    }

    private static String renderAll(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            sb.append(MsgContentRenderer.render(m, 0)).append('\n');
        }
        return sb.toString();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
