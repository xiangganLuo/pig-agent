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
 * <p>The result is collapsed to a single summary line and truncated. Credentials
 * that may show up in tool output (MCP env/headers, bearer tokens, {@code sk-} keys)
 * are redacted before rendering — per the security floor, secrets must never appear
 * in REPL output. Pure function; Jansi is used only as an ANSI string builder.
 */
public final class ToolCallFormatter {

    private static final int MAX_SUMMARY = 200;

    private ToolCallFormatter() {
    }

    public static String format(String label, String result) {
        String name = (label == null || label.isBlank()) ? "tool" : label.strip();
        String summary = summarize(result);
        String head = ansi().fg(Color.BLUE).a("⏺ ").reset().bold().a(name).reset().toString();
        String body = ansi().fgBright(Color.BLACK).a("  └ " + summary).reset().toString();
        return head + "\n" + body;
    }

    private static String summarize(String result) {
        if (result == null) {
            return "";
        }
        String oneLine = result.strip().replaceAll("\\s*\\R\\s*", " ");
        String redacted = redact(oneLine);
        if (redacted.length() > MAX_SUMMARY) {
            redacted = redacted.substring(0, MAX_SUMMARY) + "…";
        }
        return redacted;
    }

    /** Mask common secret shapes so they never reach the terminal. */
    static String redact(String text) {
        String t = text;
        t = t.replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        t = t.replaceAll("(?i)(api[_-]?key|token|secret|password|passwd|pwd)(\\s*[=:]\\s*)\\S+",
                "$1$2***");
        t = t.replaceAll("(?i)(authorization\\s*:\\s*)(bearer\\s+)?\\S+", "$1***");
        return t;
    }
}
