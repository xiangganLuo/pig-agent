package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure detector for content that MUST NOT be (recursively) summarized because summarization would
 * corrupt it: fenced/inline code, command lines, IDs/hashes (long hex, SHA, UUID) and long exact
 * quoted text. A message containing any protected span is kept verbatim rather than fed to the
 * summarizer.
 *
 * <p>Deliberately conservative — it prefers to <em>miss</em> a borderline span over misclassifying
 * ordinary natural-language chatter as protected (min-length gates + line/word anchoring). It is a
 * heuristic net, not a parser; the {@link ConsistencyChecker} + safe fallback catch anything a
 * false-negative here lets slip into the summary.
 */
public final class VerbatimGuard {

    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]{4,}`");
    private static final Pattern COMMAND_LINE = Pattern.compile(
            "(?m)^\\s*(?:\\$\\s+\\S.*|(?:git|mvn|npm|npx|yarn|pnpm|docker|kubectl|curl|wget|"
                    + "bash|sh|python|python3|pip|pip3|cargo|go|make|sudo|apt|apt-get|brew|gradle)\\b.*)");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{7,}\\b");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"\\n]{12,}\"");

    private static final Pattern[] SPAN_PATTERNS = {
            FENCED_CODE, INLINE_CODE, COMMAND_LINE, UUID, LONG_HEX, QUOTED
    };

    /** A lone/unclosed triple-backtick still signals code and must be protected. */
    private static final String FENCE_MARK = "```";

    /** Whether the message carries any protected content (rendered full content, null → false). */
    public boolean isProtected(Msg msg) {
        return containsProtected(MsgContentRenderer.render(msg, 0));
    }

    /** Whether the text carries any protected span. */
    public boolean containsProtected(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.contains(FENCE_MARK)) {
            return true;
        }
        for (Pattern p : SPAN_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** All protected spans in the text (for the consistency check); empty if none. */
    public List<String> protectedSpans(String text) {
        List<String> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }
        for (Pattern p : SPAN_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String span = m.group();
                if (span != null && !span.isBlank()) {
                    spans.add(span);
                }
            }
        }
        return spans;
    }
}
