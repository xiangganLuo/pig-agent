package io.pigagent.cli.render;

import org.fusesource.jansi.Ansi.Color;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Formats a tool call as a CC-style two-line block:
 *
 * <pre>
 * ⏺ executeCommand
 *   └ exit=0, 3 files
 * </pre>
 *
 * <p>The head ({@code ⏺ name}) and body ({@code └ summary}) are also exposed separately so the REPL
 * can print the head as soon as a tool call starts (keeping the user informed during a long command)
 * and append the body when the result arrives. A failed result renders through {@link #errorBody} —
 * a red, {@code ✗}-marked line visually distinct from a successful call.
 *
 * <p>The result is collapsed to a single summary line and truncated (on a code-point boundary, never
 * splitting a surrogate pair). Credentials that may show up in tool output (MCP env/headers, bearer
 * tokens, {@code sk-} keys, bare {@code AIza…}/{@code ghp_…}/{@code xox…}/JWT tokens) are redacted
 * before rendering — per the security floor, secrets must never appear in REPL output. Pure functions;
 * Jansi is used only as an ANSI string builder.
 */
public final class ToolCallFormatter {

    private static final int MAX_SUMMARY = 200;

    private ToolCallFormatter() {
    }

    /** The full two-line block: head + successful (dim) body. */
    public static String format(String label, String result) {
        return head(label) + "\n" + body(result);
    }

    /** The head line only: {@code ⏺ name} (blue marker + bold name). */
    public static String head(String label) {
        String name = (label == null || label.isBlank()) ? "tool" : label.strip();
        return ansi().fg(Color.BLUE).a("⏺ ").reset().bold().a(name).reset().toString();
    }

    /** The success body line: dim {@code   └ summary}. */
    public static String body(String result) {
        return ansi().fgBright(Color.BLACK).a("  └ " + summarize(result)).reset().toString();
    }

    /** The failure body line: red {@code   └ ✗ summary}, visually distinct from a success. */
    public static String errorBody(String result) {
        return ansi().fgBright(Color.RED).a("  └ ✗ " + summarize(result)).reset().toString();
    }

    private static String summarize(String result) {
        if (result == null) {
            return "";
        }
        String oneLine = result.strip().replaceAll("\\s*\\R\\s*", " ");
        String redacted = redact(oneLine);
        if (redacted.length() > MAX_SUMMARY) {
            redacted = codePointSafeSubstring(redacted, MAX_SUMMARY) + "…";
        }
        return redacted;
    }

    /**
     * Substring {@code [0, maxChars)} clamped to a code-point boundary — if {@code maxChars} would
     * fall between a surrogate pair it steps back one char, so truncation never emits a lone surrogate
     * (which renders as {@code �}). Package-private for testing.
     */
    static String codePointSafeSubstring(String s, int maxChars) {
        if (s == null || maxChars <= 0) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--; // don't split a surrogate pair
        }
        return s.substring(0, end);
    }

    /** Mask common secret shapes so they never reach the terminal. Reused across the REPL render path. */
    public static String redact(String text) {
        String t = text;
        // Bare (unprefixed) provider tokens — masked whole (defeats ?key=AIza… URL leaks etc).
        t = t.replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "***");                 // Google API key
        t = t.replaceAll("gh[pousr]_[0-9A-Za-z]{20,}", "***");              // GitHub token
        t = t.replaceAll("xox[baprs]-[0-9A-Za-z-]{10,}", "***");           // Slack bot/user token
        t = t.replaceAll("xapp-[0-9A-Za-z-]{10,}", "***");                 // Slack app token
        t = t.replaceAll("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+", "***"); // JWT
        // Prefixed / assignment shapes.
        t = t.replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        t = t.replaceAll("(?i)(api[_-]?key|token|secret|password|passwd|pwd)(\\s*[=:]\\s*)\\S+",
                "$1$2***");
        t = t.replaceAll("(?i)(authorization\\s*:\\s*)(bearer\\s+)?\\S+", "$1***");
        return t;
    }
}
