package io.pigagent.cli.render;

import org.fusesource.jansi.Ansi.Color;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Formats a <em>forwarded subagent (child) event</em> for the CC-REPL (av2 Phase 6a). A synchronous
 * local subagent's events are forwarded onto the parent's {@code streamEvents} stream tagged with a
 * {@code source} path ({@code "main/reviewer"}); this renderer turns them into a dim, nested,
 * single-line marker distinct from the parent's answer, e.g.:
 *
 * <pre>
 * ⏺ agent_spawn
 *   └ [reviewer] found 2 issues in AuthService, both null-check gaps
 * </pre>
 *
 * <p>Pure function; Jansi is used only as an ANSI string builder. Credential redaction + one-line
 * truncation are reused from {@link ToolCallFormatter} so secrets never reach the terminal and a
 * chatty child cannot flood the REPL.
 */
public final class SubagentEventRenderer {

    private static final int MAX_SUMMARY = 200;

    private SubagentEventRenderer() {
    }

    /** The short child label from a {@code source} path: {@code "main/reviewer"} → {@code "reviewer"}. */
    public static String label(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String s = source.strip();
        int slash = s.lastIndexOf('/');
        return (slash >= 0 && slash < s.length() - 1) ? s.substring(slash + 1) : s;
    }

    /**
     * A nested, dim child line: {@code "  └ [reviewer] <summary>"}. The summary is collapsed to one
     * line, credential-redacted and truncated. A blank summary yields just the labeled prefix (used for
     * lifecycle notes like a child starting).
     */
    public static String format(String source, String text) {
        String lbl = label(source);
        String summary = summarize(text);
        String content = "  └ [" + lbl + "]" + (summary.isEmpty() ? "" : " " + summary);
        return ansi().fgBright(Color.BLACK).a(content).reset().toString();
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\\s*\\R\\s*", " ");
        String redacted = ToolCallFormatter.redact(oneLine);
        if (redacted.length() > MAX_SUMMARY) {
            redacted = redacted.substring(0, MAX_SUMMARY) + "…";
        }
        return redacted;
    }
}
